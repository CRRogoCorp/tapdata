package com.tapdata.tm.commons.dag.nodes;

import cn.hutool.core.thread.ThreadUtil;
import com.tapdata.tm.commons.dag.DAG;
import com.tapdata.tm.commons.dag.Node;
import com.tapdata.tm.commons.dag.NodeType;
import com.tapdata.tm.commons.dag.SchemaTransformerResult;
import com.tapdata.tm.commons.dag.process.FieldProcessorNode;
import com.tapdata.tm.commons.dag.process.TableRenameProcessNode;
import com.tapdata.tm.commons.dag.vo.BatchTypeOperation;
import com.tapdata.tm.commons.dag.vo.FieldProcess;
import com.tapdata.tm.commons.dag.vo.SyncObjects;
import com.tapdata.tm.commons.dag.vo.TableRenameTableInfo;
import com.tapdata.tm.commons.schema.*;
import io.tapdata.entity.event.ddl.TapDDLEvent;
import io.tapdata.entity.event.ddl.table.TapFieldBaseEvent;
import com.tapdata.tm.commons.task.dto.TaskDto;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.tapdata.tm.commons.base.convert.ObjectIdDeserialize.toObjectId;

/**
 * @author lg<lirufei0808 @ gmail.com>
 * @date 2021/11/5 上午10:11
 * @description
 */
@NodeType("database")
@Getter
@Setter
@ToString
@Slf4j
public class DatabaseNode extends DataParentNode<List<Schema>> {

    private Boolean dataQualityTag;
    private Integer distance;
    private Boolean freeTransform;
    private List<String> inputLanes;
    private List<String> outputLanes;
    private String existDataProcessMode = "keepData";
    private Integer readBatchSize;
    private Integer readCdcInterval;
    private List<FieldProcess> fieldProcess;

    /**
     * 复制DAG web端改成 这个字段不传值需要从源表tableNames推出来
     */
    private List<SyncObjects> syncObjects;
    private List<BatchTypeOperation> batchOperationList;

    //包含的表名，用于数据挖掘，在加载schema的时候存入
    private List<String> tableNames;

    // 复制任务 全部 or 自定义
    private String migrateTableSelectType;

    public DatabaseNode() {
        super("database");
    }


    @Override
    public List<Schema> mergeSchema(List<List<Schema>> inputSchemas, List<Schema> schemas) {
        //把inputSchemas的deleted的field给过滤掉
        for (List<Schema> inputSchema : inputSchemas) {
            SchemaUtils.removeDeleteFields(inputSchema);
        }

        if (schemas == null) {
            schemas = new ArrayList<>();
        }

        Map<String, List<Field>> targetFieldMap = schemas.stream().collect(Collectors.toMap(Schema::getOriginalName, Schema::getFields, (k1, k2)-> k1));
        // 1. 所有源库按照表名称分组合并
        // 2. 根据规则转换源库表名称，匹配目标表并合并
        DataSourceConnectionDto dataSource = service.getDataSource(connectionId);
        String metaType = "table";
        if ("mongodb".equals(dataSource.getDatabase_type())) {
            metaType = "collection";
        }
        final String _metaType = metaType;
        List<SchemaTransformerResult> schemaTransformerResults = new ArrayList<>();
        String currentDbName = schemas.size() > 0 ? schemas.get(0).getDatabase() : null;
        List<String> inputFields = inputSchemas.stream().flatMap(Collection::stream).map(Schema::getFields).flatMap(Collection::stream).map(Field::getFieldName).collect(Collectors.toList());
        List<String> inputFieldOriginalNames = inputSchemas.stream().flatMap(Collection::stream).map(Schema::getFields).flatMap(Collection::stream).map(Field::getOriginalFieldName).collect(Collectors.toList());
        Map<String, Schema> inputTables = inputSchemas.stream().flatMap(Collection::stream).peek(s -> {

            transformResults(targetFieldMap.get(s.getOriginalName()), dataSource, _metaType, schemaTransformerResults, currentDbName, s);

        }).collect(Collectors.toMap(Schema::getOriginalName, s -> s,
                (s1, s2) -> SchemaUtils.mergeSchema(Collections.singletonList(s1), s2)));

        if (listener != null) {
            listener.schemaTransformResult(getId(), schemaTransformerResults);
        }
        List<Schema> outputSchema;
        if (schemas.size() == 0) {
            outputSchema =  new ArrayList<>(inputTables.values());
        } else {

            List<Schema> existsTables = schemas.stream().map(s -> {
                if (inputTables.containsKey(s.getOriginalName())) {
                    return SchemaUtils.mergeSchema(Collections.singletonList(inputTables.remove(s.getOriginalName())), s);
                }
                return s;
            }).collect(Collectors.toList());

            outputSchema = Stream.concat(new ArrayList<>(inputTables.values()).stream(), existsTables.stream()).collect(Collectors.toList());
        }
        for (Schema schema : outputSchema) {
            schema.setAncestorsName(schema.getOriginalName());
            schema.setFields(transformFields(inputFields, schema, inputFieldOriginalNames));
            //  has migrateFieldNode && field not show => will del index where contain field
            schema.setIndices(updateIndexDelField(schema.getIndices(), inputFieldOriginalNames));
            long count = schema.getFields().stream().filter(Field::isDeleted).count();
            long count1 = schema.getFields().stream().filter(f -> !f.isDeleted()).filter(field -> field.getFieldName().contains(".")).count();
            for (SchemaTransformerResult result : schemaTransformerResults) {
                if (schema.getOriginalName().equals(result.getSourceObjectName())) {
                    result.setUserDeletedNum((int) count);
                    result.setSourceFieldCount((int) (result.getSourceFieldCount() - count1));
                    break;
                }
            }
        }
        return outputSchema;
    }

    private List<TableIndex> updateIndexDelField(List<TableIndex> indices, List<String> inputFieldOriginalNames) {
        if (CollectionUtils.isEmpty(indices)) {
            return indices;
        }
        Iterator<TableIndex> indexIterator = indices.iterator();
        while (indexIterator.hasNext()) {
            TableIndex tableIndex = indexIterator.next();
            List<TableIndexColumn> columns = tableIndex.getColumns();

            columns.removeIf(column -> inputFieldOriginalNames.contains(column.getColumnName()));

            if (CollectionUtils.isEmpty(columns)) {
                indexIterator.remove();
            }
        }

        return indices;
    }
    @SneakyThrows
    public void transformSchema(DAG.Options options) {

        if (CollectionUtils.isEmpty(getGraph().successors(this.getId()))) {
            this.setSchema(null);
            super.transformSchema(options);
            return;
        }

        List<String> tables = getSourceNodeTableNames();

        if (CollectionUtils.isNotEmpty(tables)) {
            tableNames.removeIf(String::isEmpty);

            List<String> includes = new ArrayList<>();
            options.setIncludes(includes);
            List<List<String>> partition = ListUtils.partition(tables, options.getBatchNum());
            if ("sync".equals(options.getSyncType())) {
                partition.forEach(list -> {
                    includes.clear();
                    includes.addAll(list);
                    this.setSchema(null);
                    super.transformSchema(options);
                });
            } else {
                CountDownLatch countDownLatch = ThreadUtil.newCountDownLatch(partition.size());
                partition.forEach( list -> ThreadUtil.execute(() -> {
                    includes.clear();
                    includes.addAll(list);
                    this.setSchema(null);
                    super.transformSchema(options);
                    countDownLatch.countDown();
                }));
                countDownLatch.await();
            }
        }
    }

    public List<String> getSourceNodeTableNames() {
        AtomicReference<List<String>> tableNames = new AtomicReference<>();

        Set<String> targetNodeIds = getGraph().getSinks();

        this.getDag().getSources().stream()
                .findAny()
                .ifPresent(t -> tableNames.set(((DatabaseNode) t).getTableNames()));

        return tableNames.get();
    }

    @Override
    protected List<Schema> loadSchema(List<String> includes) {

        if (service == null || CollectionUtils.isEmpty(includes)) {
            return Collections.emptyList();
        }

        List<String> filteredTableNames;

        if (TaskDto.SYNC_TYPE_SYNC.equals(getSyncType())) {
            filteredTableNames = includes.stream()
                    .map(this::transformTableName)
                    .collect(Collectors.toList());

        } else {
            LinkedList<Node> preNodes = getPreNodes(this.getId());
            if (CollectionUtils.isNotEmpty(preNodes)) {
                LinkedList<TableRenameProcessNode> collect = preNodes.stream().filter(node -> node instanceof TableRenameProcessNode)
                        .map(node -> (TableRenameProcessNode) node)
                        .collect(Collectors.toCollection(LinkedList::new));
                if (CollectionUtils.isNotEmpty(collect)) {
                    Map<String, TableRenameTableInfo> namesMapping = collect.getLast().originalMap();
                    if (!namesMapping.isEmpty()) {
                        for (int i = 0; i < includes.size(); i++) {
                            String tableName = includes.get(i);
                            if (namesMapping.containsKey(tableName)) {
                                includes.set(i, namesMapping.get(tableName).getCurrentTableName());
                            }
                        }
                    }
                }
            }

            filteredTableNames = includes;
        }

        List<Schema> schemaList = service.loadSchema(ownerId(), toObjectId(connectionId), filteredTableNames, null)
                .stream().peek(s -> {
                    s.setAncestorsName(s.getOriginalName());
                    s.setNodeId(getId());
                    s.setSourceNodeDatabaseType(getDatabaseType());
                }).collect(Collectors.toList());

        if (CollectionUtils.isNotEmpty(schemaList)) {
            tableNames = schemaList.stream().map(Schema::getOriginalName).collect(Collectors.toList());
        }

        return schemaList;
    }

    public List<String> getFilteredTableNames() {
        List<String> filteredTableNames = syncObjects.stream()
                .filter(s -> s.getObjectNames() != null /*&& "table".equalsIgnoreCase(s.getType())*/) // type: table,topic,queue
                .flatMap(s -> s.getObjectNames().stream()).map(this::transformTableName)
                .collect(Collectors.toList());
        return filteredTableNames;
    }

    public int tableSize() {
        return getFilteredTableNames().size();
    }

    @Override
    protected List<Schema> saveSchema(Collection<String> pre, String nodeId, List<Schema> schemaList, DAG.Options options) {
        if (schemaList != null && schemaList.size() > 0) {
            schemaList.forEach(s -> {
                s.setTaskId(taskId());
                s.setNodeId(nodeId);
            });
            List<Schema> updateSchema = service.createOrUpdateSchema(ownerId(), toObjectId(connectionId), schemaList, options, this);
            //service.upsertTransformTemp(this.listener.getSchemaTransformResult(nodeId), this.getDag().getTaskId().toHexString(), nodeId, getFilteredTableNames().size(), updateSchema, options.getUuid());
        }
        return schemaList;
    }

    @Override
    protected List<Schema> cloneSchema(List<Schema> schemas) {
        if (schemas == null) {
            return Collections.emptyList();
        }
        return schemas.stream().map(SchemaUtils::cloneSchema).collect(Collectors.toList());
    }


    @Override
    protected List<Schema> filterChangedSchema(List<Schema> outputSchema, DAG.Options options) {

        if (outputSchema == null || outputSchema.size() == 0) {
            return Collections.emptyList();
        }
        List<Schema> originalSchemaList = getSchema() != null ? getSchema() : Collections.emptyList();
        Map<String, Schema> originalSchemaMap = originalSchemaList.stream().collect(Collectors.toMap(Schema::getOriginalName, s -> s, (s1, s2) -> s1));

        // 于原始模型列表比较，过滤掉没有变化过的模型
        return outputSchema.stream().filter(s -> {
            if ("all".equals(options.getRollback()) || ("table".equals(options.getRollback()) && s.getOriginalName().equals(options.getRollbackTable()))) {
                return true;
            } else if (originalSchemaMap.containsKey(s.getOriginalName()) && s.equals(originalSchemaMap.get(s.getOriginalName()))) {
                return false;
            } else {
                return true;
            }
        }).collect(Collectors.toList());
    }

    @Override
    public void fieldDdlEvent(TapDDLEvent event) throws Exception {
        if (null == fieldProcess) {
            return;
        }
        for (FieldProcess process : fieldProcess) {
            List<FieldProcessorNode.Operation> operations = process.getOperations();
            for (FieldProcessorNode.Operation operation : operations) {
                operation.matchPdkFieldEvent(event);
            }
        }
    }
}