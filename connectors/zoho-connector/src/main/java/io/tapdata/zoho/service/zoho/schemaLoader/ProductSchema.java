package io.tapdata.zoho.service.zoho.schemaLoader;

import cn.hutool.core.date.DateUtil;
import io.tapdata.entity.error.CoreException;
import io.tapdata.entity.event.TapEvent;
import io.tapdata.entity.simplify.TapSimplify;
import io.tapdata.pdk.apis.consumer.StreamReadConsumer;
import io.tapdata.pdk.apis.context.TapConnectionContext;
import io.tapdata.zoho.entity.ZoHoOffset;
import io.tapdata.zoho.service.connectionMode.ConnectionMode;
import io.tapdata.zoho.service.zoho.loader.ProductsOpenApi;
import io.tapdata.zoho.service.zoho.schema.Schemas;
import io.tapdata.zoho.utils.Checker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

public class ProductSchema implements SchemaLoader {
    private ProductsOpenApi productsOpenApi;
    @Override
    public SchemaLoader configSchema(TapConnectionContext tapConnectionContext) {
        this.productsOpenApi = ProductsOpenApi.create(tapConnectionContext);
        return this;
    }

    @Override
    public List<TapEvent> rawDataCallbackFilterFunction(Map<String, Object> issueEventData) {
        return null;
    }

    @Override
    public void streamRead(Object offsetState, int recordSize, StreamReadConsumer consumer) {

    }

    @Override
    public Object timestampToStreamOffset(Long time) {
        return null;
    }

    @Override
    public void batchRead(Object offset, int batchCount, BiConsumer<List<TapEvent>, Object> consumer) {
        this.read(batchCount,offset,consumer,Boolean.TRUE);
    }

    @Override
    public long batchCount() throws Throwable {
        return 0;
    }

    public void read(int readSize, Object offsetState, BiConsumer<List<TapEvent>, Object> consumer,boolean isBatchRead ){
        final List<TapEvent>[] events = new List[]{new ArrayList<>()};
        int pageSize = Math.min(readSize, ProductsOpenApi.MAX_PAGE_LIMIT);
        int fromPageIndex = 1;//从第几个工单开始分页
        TapConnectionContext context = this.productsOpenApi.getContext();
        String modeName = context.getConnectionConfig().getString("connectionMode");
        ConnectionMode connectionMode = ConnectionMode.getInstanceByName(context, modeName);
        if (null == connectionMode){
            throw new CoreException("Connection Mode is not empty or not null.");
        }
        String tableName =  Schemas.Products.getTableName();
        while (true){
            List<Map<String, Object>> list = productsOpenApi.page(
                    fromPageIndex,
                    pageSize,
                    isBatchRead ? ProductsOpenApi.SortBy.CREATED_TIME.descSortBy(): ProductsOpenApi.SortBy.MODIFIED_TIME.descSortBy());
            if (Checker.isNotEmpty(list) && !list.isEmpty()){
                fromPageIndex += pageSize;
                list.stream().forEach(product->{
                    Map<String, Object> oneProduct = connectionMode.attributeAssignment(product,tableName,productsOpenApi);
                    if (Checker.isNotEmpty(oneProduct) && !oneProduct.isEmpty()){
                        Object modifiedTimeObj = oneProduct.get("modifiedTime");
                        Object createdTimeObj = oneProduct.get("createdTime");
                        long referenceTime = System.currentTimeMillis();
                        if (Checker.isNotEmpty(modifiedTimeObj) && modifiedTimeObj instanceof String) {
                            String referenceTimeStr = (String) modifiedTimeObj;
                            referenceTime = DateUtil.parse(
                                    referenceTimeStr.replaceAll("Z", "").replaceAll("T", " "),
                                    "yyyy-MM-dd HH:mm:ss.SSS").getTime();
                            ((ZoHoOffset) offsetState).getTableUpdateTimeMap().put(tableName, referenceTime);
                        }
                        //创建时间和修改时间相同，表示新增事件，否则为修改事件
                        events[0].add(( String.valueOf(modifiedTimeObj).equals(String.valueOf(createdTimeObj))?
                                    TapSimplify.insertRecordEvent(oneProduct, tableName).referenceTime(referenceTime)
                                    :TapSimplify.updateDMLEvent(null,oneProduct, tableName).referenceTime(referenceTime) ));
                        if (events[0].size() == readSize){
                            consumer.accept(events[0], offsetState);
                            events[0] = new ArrayList<>();
                        }
                    }
                });
                if (events[0].size()>0){
                    consumer.accept(events[0], offsetState);
                }
            }else {
                break;
            }
        }
    }

}
