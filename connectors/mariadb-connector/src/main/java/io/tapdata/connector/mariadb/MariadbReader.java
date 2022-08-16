package io.tapdata.connector.mariadb;

import io.debezium.config.Configuration;
import io.debezium.connector.mysql.MySqlConnectorConfig;
import io.debezium.embedded.EmbeddedEngine;
import io.debezium.engine.DebeziumEngine;
import io.tapdata.connector.mariadb.ddl.DDLFactory;
import io.tapdata.connector.mariadb.ddl.DDLParserType;
import io.tapdata.connector.mariadb.entity.MariadbBinlogPosition;
import io.tapdata.connector.mariadb.entity.MariadbSnapshotOffset;
import io.tapdata.connector.mariadb.entity.MariadbStreamEvent;
import io.tapdata.connector.mariadb.entity.MariadbStreamOffset;
import io.tapdata.connector.mariadb.util.StringCompressUtil;
import io.tapdata.entity.event.TapEvent;
import io.tapdata.entity.event.ddl.TapDDLEvent;
import io.tapdata.entity.event.dml.TapDeleteRecordEvent;
import io.tapdata.entity.event.dml.TapInsertRecordEvent;
import io.tapdata.entity.event.dml.TapRecordEvent;
import io.tapdata.entity.event.dml.TapUpdateRecordEvent;
import io.tapdata.entity.logger.TapLogger;
import io.tapdata.entity.schema.TapField;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.entity.schema.type.TapDate;
import io.tapdata.entity.schema.type.TapDateTime;
import io.tapdata.entity.schema.type.TapType;
import io.tapdata.entity.simplify.TapSimplify;
import io.tapdata.entity.utils.DataMap;
import io.tapdata.entity.utils.InstanceFactory;
import io.tapdata.entity.utils.JsonParser;
import io.tapdata.entity.utils.TypeHolder;
import io.tapdata.entity.utils.cache.KVMap;
import io.tapdata.entity.utils.cache.KVReadOnlyMap;
import io.tapdata.pdk.apis.consumer.StreamReadConsumer;
import io.tapdata.pdk.apis.context.TapConnectorContext;
import io.tapdata.pdk.apis.entity.TapAdvanceFilter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.storage.OffsetUtils;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.ResultSetMetaData;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.tapdata.connector.mariadb.util.MariadbUtil.randomServerId;


public class MariadbReader implements Closeable {
	private static final String TAG = MariadbReader.class.getSimpleName();
	public static final String SERVER_NAME_KEY = "SERVER_NAME";
	public static final String MYSQL_SCHEMA_HISTORY = "MYSQL_SCHEMA_HISTORY";
	private static final String SOURCE_RECORD_DDL_KEY = "ddl";
	public static final String FIRST_TIME_KEY = "FIRST_TIME";
	private String serverName;
	private AtomicBoolean running;
	private MariadbContext mariadbContext;
	private EmbeddedEngine embeddedEngine;
	private LinkedBlockingQueue<MariadbStreamEvent> eventQueue;
	private ExecutorService streamConsumerThreadPool;
	private StreamReadConsumer streamReadConsumer;
	private ScheduledExecutorService mysqlSchemaHistoryMonitor;
	private KVReadOnlyMap<TapTable> tapTableMap;
	private DDLParserType ddlParserType = DDLParserType.CCJ_SQL_PARSER;
	private final int MIN_BATCH_SIZE = 1000;

	public MariadbReader(MariadbContext mariadbContext) {
		this.mariadbContext = mariadbContext;
		this.running = new AtomicBoolean(true);
	}

	public void readWithOffset(TapConnectorContext tapConnectorContext, TapTable tapTable, MariadbSnapshotOffset mariadbSnapshotOffset,
							   Predicate<?> stop, BiConsumer<Map<String, Object>, MariadbSnapshotOffset> consumer) throws Throwable {
		SqlMaker sqlMaker = new MariadbMaker();
		String sql = sqlMaker.selectSql(tapConnectorContext, tapTable, mariadbSnapshotOffset);
		Collection<String> pks = tapTable.primaryKeys(true);
		AtomicLong row = new AtomicLong(0L);
		this.mariadbContext.queryWithStream(sql, rs -> {
			ResultSetMetaData metaData = rs.getMetaData();
			while ((null == stop || !stop.test(null)) && rs.next()) {
				row.incrementAndGet();
				Map<String, Object> data = new HashMap<>();
				for (int i = 0; i < metaData.getColumnCount(); i++) {
					String columnName = metaData.getColumnName(i + 1);
					try {
						Object value = rs.getObject(i + 1);
						data.put(columnName, value);
						if (pks.contains(columnName)) {
							mariadbSnapshotOffset.getOffset().put(columnName, value);
						}
					} catch (Exception e) {
						throw new Exception("Read column value failed, row: " + row.get() + ", column name: " + columnName + ", data: " + data + "; Error: " + e.getMessage(), e);
					}
				}
				consumer.accept(data, mariadbSnapshotOffset);
			}
		});
	}

	public void readWithFilter(TapConnectorContext tapConnectorContext, TapTable tapTable, TapAdvanceFilter tapAdvanceFilter,
							   Predicate<?> stop, Consumer<Map<String, Object>> consumer) throws Throwable {
		SqlMaker sqlMaker = new MariadbMaker();
		String sql = sqlMaker.selectSql(tapConnectorContext, tapTable, tapAdvanceFilter);
		AtomicLong row = new AtomicLong(0L);
		this.mariadbContext.queryWithStream(sql, rs -> {
			ResultSetMetaData metaData = rs.getMetaData();
			while (rs.next()) {
				if (null != stop && stop.test(null)) {
					break;
				}
				row.incrementAndGet();
				Map<String, Object> data = new HashMap<>();
				for (int i = 0; i < metaData.getColumnCount(); i++) {
					String columnName = metaData.getColumnName(i + 1);
					try {
						Object value = rs.getObject(i + 1);
						data.put(columnName, value);
					} catch (Exception e) {
						throw new Exception("Read column value failed, row: " + row.get() + ", column name: " + columnName + ", data: " + data + "; Error: " + e.getMessage(), e);
					}
				}
				consumer.accept(data);
			}
		});
	}

	public void readBinlog(TapConnectorContext tapConnectorContext, List<String> tables,
						   Object offset, int batchSize, DDLParserType ddlParserType, StreamReadConsumer consumer) throws Throwable {
		try {
			batchSize = Math.max(batchSize, MIN_BATCH_SIZE);
			initDebeziumServerName(tapConnectorContext);
			this.tapTableMap = tapConnectorContext.getTableMap();
			this.ddlParserType = ddlParserType;
			String offsetStr = "";
			JsonParser jsonParser = InstanceFactory.instance(JsonParser.class);
			MariadbStreamOffset mariadbStreamOffset = null;
			if (offset instanceof MariadbStreamOffset) {
				mariadbStreamOffset = (MariadbStreamOffset) offset;
			} else if (offset instanceof MariadbBinlogPosition) {
				mariadbStreamOffset = binlogPosition2MariadbStreamOffset((MariadbBinlogPosition) offset, jsonParser);
			}
			if (null != mariadbStreamOffset) {
				offsetStr = jsonParser.toJson(mariadbStreamOffset);
			}
			AtomicReference<Throwable> throwableAtomicReference = new AtomicReference<>();
			TapLogger.info(TAG, "Starting mysql cdc, server name: " + serverName);
			this.eventQueue = new LinkedBlockingQueue<>(10);
			this.streamReadConsumer = consumer;
			this.streamConsumerThreadPool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, new SynchronousQueue<>());
			this.streamConsumerThreadPool.submit(this::eventQueueConsumer);
			DataMap connectionConfig = tapConnectorContext.getConnectionConfig();
			String database = connectionConfig.getString("database");
			initMysqlSchemaHistory(tapConnectorContext);
			this.mysqlSchemaHistoryMonitor = new ScheduledThreadPoolExecutor(1);
			this.mysqlSchemaHistoryMonitor.scheduleAtFixedRate(() -> saveMysqlSchemaHistory(tapConnectorContext),
					10L, 10L, TimeUnit.SECONDS);
			Configuration.Builder builder = Configuration.create()
					.with("name", serverName)
					.with("connector.class", "io.debezium.connector.mysql.MySqlConnector")
					.with("database.hostname", connectionConfig.getString("host"))
					.with("database.port", Integer.parseInt(connectionConfig.getString("port")))
					.with("database.user", connectionConfig.getString("username"))
					.with("database.password", connectionConfig.getString("password"))
					.with("database.server.name", serverName)
					.with("threadName", "Debezium-Mysql-Connector-" + serverName)
					.with("database.history.skip.unparseable.ddl", true)
					.with("database.history.store.only.monitored.tables.ddl", true)
					.with("database.history.store.only.captured.tables.ddl", true)
					.with(MySqlConnectorConfig.SNAPSHOT_LOCKING_MODE, MySqlConnectorConfig.SnapshotLockingMode.NONE)
					.with("max.queue.size", batchSize * 8)
					.with("max.batch.size", batchSize)
					.with(MySqlConnectorConfig.SERVER_ID, randomServerId())
					.with("time.precision.mode", "adaptive_time_microseconds");
			List<String> dbTableNames = tables.stream().map(t -> database + "." + t).collect(Collectors.toList());
			builder.with(MySqlConnectorConfig.DATABASE_INCLUDE_LIST, database);
			builder.with(MySqlConnectorConfig.TABLE_INCLUDE_LIST, String.join(",", dbTableNames));
			builder.with("snapshot.mode", "schema_only_recovery");

			builder.with("database.history", "io.tapdata.connector.mariadb.StateMapHistoryBackingStore");
			builder.with(EmbeddedEngine.OFFSET_STORAGE, "io.tapdata.connector.mariadb.PdkPersistenceOffsetBackingStore");
			if (StringUtils.isNotBlank(offsetStr)) {
				builder.with("pdk.offset.string", offsetStr);
			}
			Configuration configuration = builder.build();
			StringBuilder configStr = new StringBuilder("Starting binlog reader with config {\n");
			configuration.withMaskedPasswords().asMap().forEach((k, v) -> configStr.append("  ")
					.append(k)
					.append(": ")
					.append(v)
					.append("\n"));
			configStr.append("}");
			TapLogger.info(TAG, configStr.toString());
			embeddedEngine = (EmbeddedEngine) new EmbeddedEngine.BuilderImpl()
					.using(configuration)
					.notifying(this::sourceRecordConsumer)
					.using(new DebeziumEngine.ConnectorCallback() {
						@Override
						public void taskStarted() {
							streamReadConsumer.streamReadStarted();
						}
					})
					.using((result, message, throwable) -> {
						if (result) {
							if (StringUtils.isNotBlank(message)) {
								TapLogger.info(TAG, "CDC engine stopped: " + message);
							} else {
								TapLogger.info(TAG, "CDC engine stopped");
							}
						} else {
							if (null != throwable) {
								if (StringUtils.isNotBlank(message)) {
									throwableAtomicReference.set(new RuntimeException(message, throwable));
								} else {
									throwableAtomicReference.set(new RuntimeException(throwable));
								}
							} else {
								throwableAtomicReference.set(new RuntimeException(message));
							}
						}
						streamReadConsumer.streamReadEnded();
					})
					.build();
			embeddedEngine.run();
			if (null != throwableAtomicReference.get()) {
				throw throwableAtomicReference.get();
			}
		} finally {
			Optional.ofNullable(streamConsumerThreadPool).ifPresent(ExecutorService::shutdownNow);
			Optional.ofNullable(mysqlSchemaHistoryMonitor).ifPresent(ExecutorService::shutdownNow);
			TapLogger.info(TAG, "Mysql binlog reader stopped");
		}
	}

	private MariadbStreamOffset binlogPosition2MariadbStreamOffset(MariadbBinlogPosition offset, JsonParser jsonParser) throws Throwable {
		String serverId = mariadbContext.getServerId();
		Map<String, Object> partitionMap = new HashMap<>();
		partitionMap.put("server", serverName);
		Map<String, Object> offsetMap = new HashMap<>();
		offsetMap.put("file", offset.getFilename());
		offsetMap.put("pos", offset.getPosition());
		offsetMap.put("server_id", serverId);
		MariadbStreamOffset mysqlStreamOffset = new MariadbStreamOffset();
		mysqlStreamOffset.setName(serverName);
		mysqlStreamOffset.setOffset(new HashMap<String, String>() {{
			put(jsonParser.toJson(partitionMap), jsonParser.toJson(offsetMap));
		}});
		return mysqlStreamOffset;
	}

	private void initMysqlSchemaHistory(TapConnectorContext tapConnectorContext) {
		KVMap<Object> stateMap = tapConnectorContext.getStateMap();
		Object mysqlSchemaHistory = stateMap.get(MYSQL_SCHEMA_HISTORY);
		if (mysqlSchemaHistory instanceof String) {
			try {
				mysqlSchemaHistory = StringCompressUtil.uncompress((String) mysqlSchemaHistory);
			} catch (IOException e) {
				throw new RuntimeException("Uncompress Mysql schema history failed, string: " + mysqlSchemaHistory, e);
			}
			mysqlSchemaHistory = InstanceFactory.instance(JsonParser.class).fromJson((String) mysqlSchemaHistory,
					new TypeHolder<Map<String, LinkedHashSet<String>>>() {
					});
			MariadbSchemaHistoryTransfer.historyMap.putAll((Map) mysqlSchemaHistory);
		}
	}

	private void saveMysqlSchemaHistory(TapConnectorContext tapConnectorContext) {
		Thread.currentThread().setName("Save-Mysql-Schema-History-" + serverName);
		if (!MariadbSchemaHistoryTransfer.isSave()) {
			MariadbSchemaHistoryTransfer.executeWithLock(n -> !running.get(), () -> {
				String json = InstanceFactory.instance(JsonParser.class).toJson(MariadbSchemaHistoryTransfer.historyMap);
				try {
					json = StringCompressUtil.compress(json);
				} catch (IOException e) {
					TapLogger.warn(TAG, "Compress Mysql schema history failed, string: " + json + ", error message: " + e.getMessage() + "\n" + TapSimplify.getStackString(e));
					return;
				}
				tapConnectorContext.getStateMap().put(MYSQL_SCHEMA_HISTORY, json);
				MariadbSchemaHistoryTransfer.save();
			});
		}
	}

	private void initDebeziumServerName(TapConnectorContext tapConnectorContext) {
		this.serverName = UUID.randomUUID().toString().toLowerCase();
		KVMap<Object> stateMap = tapConnectorContext.getStateMap();
		Object serverNameFromStateMap = stateMap.get(SERVER_NAME_KEY);
		if (serverNameFromStateMap instanceof String) {
			this.serverName = String.valueOf(serverNameFromStateMap);
			stateMap.put(FIRST_TIME_KEY, false);
		} else {
			stateMap.put(SERVER_NAME_KEY, this.serverName);
			stateMap.put(FIRST_TIME_KEY, true);
		}
	}

	@Override
	public void close() {
		this.running.set(false);
		Optional.ofNullable(embeddedEngine).ifPresent(engine -> {
			try {
				engine.close();
			} catch (IOException e) {
				TapLogger.warn(TAG, "Close CDC engine failed, error: " + e.getMessage() + "\n" + TapSimplify.getStackString(e));
			}
		});
	}

	private void sourceRecordConsumer(SourceRecord record) {
		if (null == record || null == record.value()) {
			return;
		}
		Schema valueSchema = record.valueSchema();
		MariadbStreamEvent mariadbStreamEvent;
		if (null != valueSchema.field("op")) {
			mariadbStreamEvent = wrapDML(record);
			Optional.ofNullable(mariadbStreamEvent).ifPresent(this::enqueue);
		} else if (null != valueSchema.field("ddl")) {
			List<MariadbStreamEvent> mysqlStreamEvents = wrapDDL(record);
			if (null != mysqlStreamEvents && mysqlStreamEvents.size() > 0) {
				mysqlStreamEvents.forEach(this::enqueue);
			}
		}
	}

	private MariadbStreamEvent wrapDML(SourceRecord record) {
		TapRecordEvent tapRecordEvent = null;
		MariadbStreamEvent mysqlStreamEvent;
		Schema valueSchema = record.valueSchema();
		Struct value = (Struct) record.value();
		Struct source = value.getStruct("source");
		Long eventTime = source.getInt64("ts_ms");
		String table = source.getString("table");
		String op = value.getString("op");
		MysqlOpType mysqlOpType = MysqlOpType.fromOp(op);
		if (null == mysqlOpType) {
			TapLogger.debug(TAG, "Unrecognized operation type: " + op + ", will skip it, record: " + record);
			return null;
		}
		Map<String, Object> before = null;
		Map<String, Object> after = null;
		switch (mysqlOpType) {
			case INSERT:
				tapRecordEvent = new TapInsertRecordEvent().init();
				if (null == valueSchema.field("after")) {
					throw new RuntimeException("Found insert record does not have after: " + record);
				}
				after = struct2Map(value.getStruct("after"), table);
				((TapInsertRecordEvent) tapRecordEvent).setAfter(after);
				break;
			case UPDATE:
				tapRecordEvent = new TapUpdateRecordEvent().init();
				if (null != valueSchema.field("before")) {
					before = struct2Map(value.getStruct("before"), table);
					((TapUpdateRecordEvent) tapRecordEvent).setBefore(before);
				}
				if (null == valueSchema.field("after")) {
					throw new RuntimeException("Found update record does not have after: " + record);
				}
				after = struct2Map(value.getStruct("after"), table);
				((TapUpdateRecordEvent) tapRecordEvent).setAfter(after);
				break;
			case DELETE:
				tapRecordEvent = new TapDeleteRecordEvent().init();
				if (null == valueSchema.field("before")) {
					throw new RuntimeException("Found delete record does not have before: " + record);
				}
				before = struct2Map(value.getStruct("before"), table);
				((TapDeleteRecordEvent) tapRecordEvent).setBefore(before);
				break;
			default:
				break;
		}
		tapRecordEvent.setTableId(table);
		tapRecordEvent.setReferenceTime(eventTime);
		MariadbStreamOffset mariadbStreamOffset = getMariadbStreamOffset(record);
		TapLogger.debug(TAG, "Read DML - Table: " + table + "\n  - Operation: " + mysqlOpType.getOp()
				+ "\n  - Before: " + before + "\n  - After: " + after + "\n  - Offset: " + mariadbStreamOffset);
		mysqlStreamEvent = new MariadbStreamEvent(tapRecordEvent, mariadbStreamOffset);
		return mysqlStreamEvent;
	}

	private List<MariadbStreamEvent> wrapDDL(SourceRecord record) {
		List<MariadbStreamEvent> mysqlStreamEvents = new ArrayList<>();
		Object value = record.value();
		if (!(value instanceof Struct)) {
			return null;
		}
		Struct structValue = (Struct) value;
		Struct source = structValue.getStruct("source");
		Long eventTime = source.getInt64("ts_ms");
		String ddlStr = structValue.getString(SOURCE_RECORD_DDL_KEY);
		TapLogger.info(TAG, "Read DDL: " + ddlStr + ", about to be packaged as some event(s)");
		MariadbStreamOffset mysqlStreamOffset = getMariadbStreamOffset(record);
		if (StringUtils.isNotBlank(ddlStr)) {
			try {
				DDLFactory.ddlToTapDDLEvent(
						ddlParserType,
						ddlStr,
						tapTableMap,
						tapDDLEvent -> {
							MariadbStreamEvent mysqlStreamEvent = new MariadbStreamEvent(tapDDLEvent, mysqlStreamOffset);
							tapDDLEvent.setReferenceTime(eventTime);
							mysqlStreamEvents.add(mysqlStreamEvent);
						}
				);
			} catch (Throwable e) {
				throw new RuntimeException("Handle ddl failed: " + ddlStr + ", error: " + e.getMessage(), e);
			}
		}
		printDDLEventLog(mysqlStreamEvents);
		return mysqlStreamEvents;
	}

	private void printDDLEventLog(List<MariadbStreamEvent> mariadbStreamEvents) {
		if (CollectionUtils.isEmpty(mariadbStreamEvents)) {
			return;
		}
		for (MariadbStreamEvent mariadbStreamEvent : mariadbStreamEvents) {
			if (null == mariadbStreamEvent || null == mariadbStreamEvent.getTapEvent()) {
				continue;
			}
			TapEvent tapEvent = mariadbStreamEvent.getTapEvent();
			if (!(tapEvent instanceof TapDDLEvent)) {
				continue;
			}
			TapLogger.info(TAG, "DDL event  - Table: " + ((TapDDLEvent) tapEvent).getTableId()
					+ "\n  - Event type: " + tapEvent.getClass().getSimpleName()
					+ "\n  - Offset: " + mariadbStreamEvent.getMysqlStreamOffset());
		}
	}

	private Map<String, Object> struct2Map(Struct struct, String table) {
		if (null == struct) {
			return null;
		}
		Map<String, Object> result = new HashMap<>();
		Schema schema = struct.schema();
		if (null == schema) {
			return null;
		}
		for (Field field : schema.fields()) {
			String fieldName = field.name();
			Object value = struct.get(fieldName);
			if (value instanceof ByteBuffer) {
				value = ((ByteBuffer) value).array();
			}
			value = handleDatetime(table, fieldName, value);
			result.put(fieldName, value);
		}
		return result;
	}

	private Object handleDatetime(String table, String fieldName, Object value) {
		TapTable tapTable = tapTableMap.get(table);
		if (null == tapTable) {
			return value;
		}
		if (null == tapTable.getNameFieldMap()) {
			return value;
		}
		TapField tapField = tapTable.getNameFieldMap().get(fieldName);
		if (null == tapField) {
			return value;
		}
		TapType tapType = tapField.getTapType();
		if (tapType instanceof TapDateTime) {
			if (((TapDateTime) tapType).getFraction().equals(0) && value instanceof Long) {
				value = ((Long) value) / 1000;
			} else if (value instanceof String) {
				try {
					value = Instant.parse((CharSequence) value);
				} catch (Exception ignored) {
				}
			}
		} else if (tapType instanceof TapDate) {
			if (value instanceof Integer) {
				value = (Integer) value * 24 * 60 * 60 * 1000L;
			}
		}
		return value;
	}

	private MariadbStreamOffset getMariadbStreamOffset(SourceRecord record) {
		MariadbStreamOffset mysqlStreamOffset = new MariadbStreamOffset();
		Map<String, Object> partition = (Map<String, Object>) record.sourcePartition();
		Map<String, Object> offset = (Map<String, Object>) record.sourceOffset();
		// Offsets are specified as schemaless to the converter, using whatever internal schema is appropriate
		// for that data. The only enforcement of the format is here.
		OffsetUtils.validateFormat(partition);
		OffsetUtils.validateFormat(offset);
		// When serializing the key, we add in the namespace information so the key is [namespace, real key]
		Map<String, String> offsetMap = new HashMap<>(1);
		String key = InstanceFactory.instance(JsonParser.class).toJson(partition);
		String value = InstanceFactory.instance(JsonParser.class).toJson(offset);
		offsetMap.put(key, value);
		mysqlStreamOffset.setOffset(offsetMap);
		mysqlStreamOffset.setName(serverName);
		return mysqlStreamOffset;
	}

	private void eventQueueConsumer() {
		while (running.get()) {
			MariadbStreamEvent mariadbStreamEvent;
			try {
				mariadbStreamEvent = eventQueue.poll(3L, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				break;
			}
			if (null == mariadbStreamEvent) {
				continue;
			}
			ArrayList<TapEvent> events = new ArrayList<>(1);
			events.add(mariadbStreamEvent.getTapEvent());
			streamReadConsumer.accept(events, mariadbStreamEvent.getMysqlStreamOffset());
		}
	}

	private void enqueue(MariadbStreamEvent mysqlStreamEvent) {
		while (running.get()) {
			try {
				if (eventQueue.offer(mysqlStreamEvent, 3L, TimeUnit.SECONDS)) {
					break;
				}
			} catch (InterruptedException e) {
				break;
			}
		}
	}

	enum MysqlOpType {
		INSERT("c"),
		UPDATE("u"),
		DELETE("d"),
		;
		private String op;

		MysqlOpType(String op) {
			this.op = op;
		}

		public String getOp() {
			return op;
		}

		private static Map<String, MysqlOpType> map;

		static {
			map = new HashMap<>();
			for (MysqlOpType value : MysqlOpType.values()) {
				map.put(value.getOp(), value);
			}
		}

		public static MysqlOpType fromOp(String op) {
			return map.get(op);
		}
	}
}
