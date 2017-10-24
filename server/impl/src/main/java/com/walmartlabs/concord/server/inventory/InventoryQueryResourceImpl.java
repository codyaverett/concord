package com.walmartlabs.concord.server.inventory;

import com.walmartlabs.concord.server.api.OperationResult;
import com.walmartlabs.concord.server.api.inventory.CreateInventoryQueryResponse;
import com.walmartlabs.concord.server.api.inventory.DeleteInventoryQueryResponse;
import com.walmartlabs.concord.server.api.inventory.InventoryQueryEntry;
import com.walmartlabs.concord.server.api.inventory.InventoryQueryResource;
import org.sonatype.siesta.Resource;
import org.sonatype.siesta.ValidationErrorsException;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Named
public class InventoryQueryResourceImpl implements InventoryQueryResource, Resource {

    private final InventoryDao inventoryDao;
    private final InventoryQueryDao inventoryQueryDao;
    private final InventoryQueryExecDao inventoryQueryExecDao;

    @Inject
    public InventoryQueryResourceImpl(InventoryDao inventoryDao, InventoryQueryDao inventoryQueryDao, InventoryQueryExecDao inventoryQueryExecDao) {
        this.inventoryDao = inventoryDao;
        this.inventoryQueryDao = inventoryQueryDao;
        this.inventoryQueryExecDao = inventoryQueryExecDao;
    }

    @Override
    public InventoryQueryEntry get(String inventoryName, String queryName) {
        UUID inventoryId = assertInventory(inventoryName);
        UUID queryId = assertQuery(inventoryId, queryName);
        return inventoryQueryDao.get(queryId);
    }

    @Override
    public CreateInventoryQueryResponse createOrUpdate(String inventoryName, String queryName, String text) {
        UUID inventoryId = assertInventory(inventoryName);
        UUID queryId = inventoryQueryDao.getId(inventoryId, queryName);

        if (queryId == null) {
            queryId = inventoryQueryDao.insert(inventoryId, queryName, text);
            return new CreateInventoryQueryResponse(OperationResult.CREATED, queryId);
        } else {
            inventoryQueryDao.update(queryId, inventoryId, queryName, text);
            return new CreateInventoryQueryResponse(OperationResult.UPDATED, queryId);
        }
    }

    @Override
    public DeleteInventoryQueryResponse delete(String inventoryName, String queryName) {
        UUID inventoryId = assertInventory(inventoryName);
        UUID queryId = assertQuery(inventoryId, queryName);
        inventoryQueryDao.delete(queryId);
        return new DeleteInventoryQueryResponse();
    }

    @Override
    public List<Object> exec(String inventoryName, String queryName, Map<String, Object> params) {
        UUID inventoryId = assertInventory(inventoryName);
        UUID queryId = assertQuery(inventoryId, queryName);
        return inventoryQueryExecDao.exec(queryId, params);
    }

    private UUID assertInventory(String inventoryName) {
        if (inventoryName == null) {
            throw new ValidationErrorsException("A valid inventory name is required");
        }

        UUID id = inventoryDao.getId(inventoryName);
        if (id == null) {
            throw new ValidationErrorsException("Inventory not found: " + inventoryName);
        }
        return id;
    }

    private UUID assertQuery(UUID inventoryId, String queryName) {
        if (queryName == null) {
            throw new ValidationErrorsException("A valid query name is required");
        }

        UUID id = inventoryQueryDao.getId(inventoryId, queryName);
        if (id == null) {
            throw new ValidationErrorsException("Query nor found: " + queryName);
        }
        return id;
    }
}
