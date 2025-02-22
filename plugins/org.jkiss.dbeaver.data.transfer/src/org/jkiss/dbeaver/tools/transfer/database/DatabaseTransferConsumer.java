/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2019 Serge Rider (serge@jkiss.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.tools.transfer.database;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.*;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDAttributeBindingCustom;
import org.jkiss.dbeaver.model.data.DBDValueHandler;
import org.jkiss.dbeaver.model.exec.*;
import org.jkiss.dbeaver.model.impl.AbstractExecutionSource;
import org.jkiss.dbeaver.model.impl.DBObjectNameCaseTransformer;
import org.jkiss.dbeaver.model.meta.DBSerializable;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DBRRunnableContext;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLDataSource;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.struct.*;
import org.jkiss.dbeaver.model.struct.rdb.DBSCatalog;
import org.jkiss.dbeaver.model.struct.rdb.DBSManipulationType;
import org.jkiss.dbeaver.model.struct.rdb.DBSSchema;
import org.jkiss.dbeaver.model.task.DBTTask;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.serialize.DBPObjectSerializer;
import org.jkiss.dbeaver.tools.transfer.IDataTransferConsumer;
import org.jkiss.dbeaver.tools.transfer.IDataTransferNodePrimary;
import org.jkiss.dbeaver.tools.transfer.IDataTransferProcessor;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

/**
 * Stream transfer consumer
 */
@DBSerializable("databaseTransferConsumer")
public class DatabaseTransferConsumer implements IDataTransferConsumer<DatabaseConsumerSettings, IDataTransferProcessor>, IDataTransferNodePrimary {

    private static final Log log = Log.getLog(DatabaseTransferConsumer.class);

    private DBSDataContainer sourceObject;
    private DBSDataManipulator targetObject;
    private DatabaseConsumerSettings settings;
    private DatabaseMappingContainer containerMapping;
    private ColumnMapping[] columnMappings;
    private DBDAttributeBinding[] sourceBindings;
    private DBCExecutionContext targetContext;
    private DBCSession targetSession;
    private DBSDataManipulator.ExecuteBatch executeBatch;
    private long rowsExported = 0;
    private boolean ignoreErrors = false;
    private List<DBSEntityAttribute> targetAttributes;
    private boolean useIsolatedConnection;

    private static class ColumnMapping {
        DBDAttributeBinding sourceAttr;
        DatabaseMappingAttribute targetAttr;
        DBDValueHandler sourceValueHandler;
        DBDValueHandler targetValueHandler;
        int targetIndex = -1;

        private ColumnMapping(DBDAttributeBinding sourceAttr) {
            this.sourceAttr = sourceAttr;
        }
    }

    public DatabaseTransferConsumer() {
    }

    public DatabaseTransferConsumer(DBSDataManipulator targetObject) {
        this.targetObject = targetObject;
    }

    @Override
    public DBSObject getDatabaseObject() {
        return targetObject;
    }

    @Override
    public void fetchStart(DBCSession session, DBCResultSet resultSet, long offset, long maxRows) throws DBCException {
        initExporter(session.getProgressMonitor());

        AbstractExecutionSource executionSource = new AbstractExecutionSource(sourceObject, targetContext, this);

        if (offset <= 0 && settings.isTruncateBeforeLoad() && (containerMapping == null || containerMapping.getMappingType() == DatabaseMappingType.existing)) {
            // Truncate target tables
            if ((targetObject.getSupportedFeatures() & DBSDataManipulator.DATA_TRUNCATE) != 0) {
                targetObject.truncateData(
                    targetSession,
                    executionSource);
            } else {
                log.error("Table '" + targetObject.getName() + "' doesn't support truncate operation");
            }
        }

        DBDAttributeBinding[] rsAttributes = DBUtils.makeLeafAttributeBindings(session, sourceObject, resultSet);
        columnMappings = new ColumnMapping[rsAttributes.length];
        sourceBindings = rsAttributes;
        targetAttributes = new ArrayList<>(columnMappings.length);
        for (int i = 0; i < rsAttributes.length; i++) {
            if (isSkipColumn(rsAttributes[i])) {
                continue;
            }
            ColumnMapping columnMapping = new ColumnMapping(rsAttributes[i]);
            if (containerMapping == null) {
                // No explicit mappings. Mapping must be provided by data producer
                // Map all attributes directly.
                if (targetObject instanceof DBSEntity) {
                    try {
                        DBSEntityAttribute attribute = ((DBSEntity) targetObject).getAttribute(session.getProgressMonitor(), columnMapping.sourceAttr.getName());
                        if (attribute != null) {
                            columnMapping.targetAttr = new DatabaseMappingAttribute(null, columnMapping.sourceAttr);
                            columnMapping.targetAttr.setTarget(attribute);
                            columnMapping.targetAttr.setMappingType(DatabaseMappingType.existing);
                        }
                    } catch (DBException e) {
                        log.error("Error getting target attribute");
                    }
                }
                if (columnMapping.targetAttr == null) {
                    throw new DBCException("Can't resolve target attribute for [" + columnMapping.sourceAttr.getName() + "]");
                }
            } else {
                columnMapping.targetAttr = containerMapping.getAttributeMapping(columnMapping.sourceAttr);
                if (columnMapping.targetAttr == null) {
                    throw new DBCException("Can't find target attribute [" + columnMapping.sourceAttr.getName() + "]");
                }
            }
            if (columnMapping.targetAttr.getMappingType() == DatabaseMappingType.skip) {
                continue;
            }
            DBSEntityAttribute targetAttr = columnMapping.targetAttr.getTarget();
            if (targetAttr == null) {
                if (columnMapping.targetAttr.getSource() instanceof DBSEntityAttribute) {
                    // Use source attr. Some datasource (e.g. document oriented do not have strict set of attributes)
                    targetAttr = (DBSEntityAttribute) columnMapping.targetAttr.getSource();
                } else {
                    throw new DBCException("Target attribute for [" + columnMapping.sourceAttr.getName() + "] wasn't resolved");
                }
            }
            columnMapping.sourceValueHandler = columnMapping.sourceAttr.getValueHandler();
            columnMapping.targetValueHandler = DBUtils.findValueHandler(targetSession.getDataSource(), targetAttr);
            columnMapping.targetIndex = targetAttributes.size();

            columnMappings[i] = columnMapping;

            targetAttributes.add(targetAttr);
        }
        DBSAttributeBase[] attributes = targetAttributes.toArray(new DBSAttributeBase[0]);

        if (targetObject instanceof DBSDataManipulatorExt) {
            ((DBSDataManipulatorExt) targetObject).beforeDataChange(session, DBSManipulationType.INSERT, attributes, executionSource);
        }

        executeBatch = targetObject.insertData(
            targetSession,
            attributes,
            null,
            executionSource);
    }

    private boolean isSkipColumn(DBDAttributeBinding attr) {
        return attr.isPseudoAttribute() || (!settings.isTransferAutoGeneratedColumns() && attr.isAutoGenerated());
    }

    @Override
    public void fetchRow(DBCSession session, DBCResultSet resultSet) throws DBCException {
        Object[] rowValues = new Object[targetAttributes.size()];
        for (int i = 0; i < columnMappings.length; i++) {
            ColumnMapping column = columnMappings[i];
            if (column == null || column.targetIndex < 0) {
                continue;
            }
            final Object attrValue;
            if (column.sourceValueHandler != null) {
                if (column.sourceAttr instanceof DBDAttributeBindingCustom) {
                    attrValue = DBUtils.getAttributeValue(column.sourceAttr, sourceBindings, rowValues);
                } else {
                    attrValue = column.sourceValueHandler.fetchValueObject(session, resultSet, column.sourceAttr, i);
                }
            } else {
                // No value handler - get raw value
                attrValue = resultSet.getAttributeValue(i);
            }
            DatabaseMappingAttribute targetAttr = column.targetAttr;
            rowValues[column.targetIndex] = column.targetValueHandler.getValueFromObject(
                targetSession,
                targetAttr.getTarget() == null ? targetAttr.getSource() : targetAttr.getTarget(),
                attrValue,
                false);
        }
        executeBatch.add(rowValues);

        rowsExported++;
        // No need. mnitor is incremented in data reader
        //session.getProgressMonitor().worked(1);

        insertBatch(false);
    }

    private void insertBatch(boolean force) throws DBCException {
        boolean needCommit = force || ((rowsExported % settings.getCommitAfterRows()) == 0);
        if (needCommit && executeBatch != null) {
            boolean retryInsert;
            do {
                retryInsert = false;
                try {
                    executeBatch.execute(targetSession);
                } catch (Throwable e) {
                    log.error("Error inserting row", e);
                    if (!ignoreErrors) {
                        switch (DBWorkbench.getPlatformUI().showErrorStopRetryIgnore(
                            "Error occurred during data load", e, true)) {
                            case STOP:
                                // just stop execution
                                throw new DBCException("Can't insert row", e);
                            case RETRY:
                                // do it again
                                retryInsert = true;
                                break;
                            case IGNORE:
                                // Just do nothing and go to the next row
                                retryInsert = false;
                                break;
                            case IGNORE_ALL:
                                ignoreErrors = true;
                                retryInsert = false;
                                break;
                        }
                    }
                }
            } while (retryInsert);
        }
        if (settings.isUseTransactions() && needCommit) {
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(targetSession.getExecutionContext());
            if (txnManager != null && !txnManager.isAutoCommit()) {
                txnManager.commit(targetSession);
            }
        }
    }

    @Override
    public void fetchEnd(DBCSession session, DBCResultSet resultSet) throws DBCException {
        try {
            if (rowsExported > 0) {
                insertBatch(true);
            }
            if (executeBatch != null) {
                executeBatch.close();
                executeBatch = null;
            }
        } finally {
            if (targetObject instanceof DBSDataManipulatorExt) {
                ((DBSDataManipulatorExt) targetObject).afterDataChange(
                    session,
                    DBSManipulationType.INSERT,
                    targetAttributes.toArray(new DBSAttributeBase[0]),
                    new AbstractExecutionSource(sourceObject, targetContext, this));
            }
        }
    }

    @Override
    public void close() {
        closeExporter();
    }

    private void initExporter(DBRProgressMonitor monitor) throws DBCException {
        DBSObject targetDB = checkTargetContainer();

        DBPDataSourceContainer dataSourceContainer = targetDB.getDataSource().getContainer();
        if (!dataSourceContainer.hasModifyPermission(DBPDataSourcePermission.PERMISSION_IMPORT_DATA)) {
            throw new DBCException("Data transfer to database [" + dataSourceContainer.getName() + "] restricted by connection configuration");
        }

        try {
            useIsolatedConnection = settings.isOpenNewConnections() && !dataSourceContainer.getDriver().isEmbedded();
            targetContext = useIsolatedConnection ?
                DBUtils.getObjectOwnerInstance(targetDB).openIsolatedContext(monitor, "Data transfer consumer") : DBUtils.getDefaultContext(targetDB, false);
        } catch (DBException e) {
            throw new DBCException("Error opening new connection", e);
        }
        targetSession = targetContext.openSession(monitor, DBCExecutionPurpose.UTIL, "Data load");
        targetSession.enableLogging(false);
        if (settings.isUseTransactions()) {
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(targetSession.getExecutionContext());
            if (txnManager != null) {
                txnManager.setAutoCommit(monitor, false);
            }
        }
    }

    private DBSObject checkTargetContainer() throws DBCException {
        if (targetObject == null && settings.getContainer() == null) {
            throw new DBCException("Can't initialize database consumer. No target object and no taregt container");
        }
        containerMapping = sourceObject == null ? null : settings.getDataMapping(sourceObject);

        return targetObject == null ? settings.getContainer() : targetObject;
    }

    private void closeExporter() {
        try {
            if (targetSession != null) {
                targetSession.close();
                targetSession = null;
            }
        } catch (Throwable e) {
            log.debug(e);
        }
        if (targetContext != null && useIsolatedConnection) {
            targetContext.close();
            targetContext = null;
        }
    }

    @Override
    public void initTransfer(DBSObject sourceObject, DatabaseConsumerSettings settings, TransferParameters parameters, IDataTransferProcessor processor, Map<Object, Object> processorProperties) {
        this.sourceObject = (DBSDataContainer) sourceObject;
        this.settings = settings;
    }

    @Override
    public void startTransfer(DBRProgressMonitor monitor) throws DBException {
        // Create all necessary database objects
        monitor.beginTask("Create necessary database objects", 1);
        try {
            DBSObject dbObject = checkTargetContainer();

            boolean hasNewObjects = false;
            if (containerMapping != null) {
                DBSObjectContainer container = settings.getContainer();
                if (container == null) {
                    throw new DBException("No target datasource - can't create target objects");
                }
                targetObject = containerMapping.getTarget();

                try (DBCSession session = DBUtils.openMetaSession(monitor, dbObject, "Create target metadata")) {
                    {
                        switch (containerMapping.getMappingType()) {
                            case create:
                                createTargetTable(session, containerMapping);
                                hasNewObjects = true;
                                break;
                            case existing:
                                for (DatabaseMappingAttribute attr : containerMapping.getAttributeMappings(monitor)) {
                                    if (attr.getMappingType() == DatabaseMappingType.create) {
                                        createTargetAttribute(session, attr);
                                        hasNewObjects = true;
                                    }
                                }
                                break;
                        }
                    }
                }

                if (hasNewObjects) {
                    // Refresh node
                    monitor.subTask("Refresh navigator model");
                    settings.getContainerNode().refreshNode(monitor, this);

                    // Reflect database changes in mappings
                    {
                        switch (containerMapping.getMappingType()) {
                            case create:
                                DBSObject newTarget = container.getChild(monitor, containerMapping.getTargetName());
                                if (newTarget == null) {
                                    throw new DBCException("New table " + containerMapping.getTargetName() + " not found in container " + DBUtils.getObjectFullName(container, DBPEvaluationContext.UI));
                                } else if (!(newTarget instanceof DBSDataManipulator)) {
                                    throw new DBCException("New table " + DBUtils.getObjectFullName(newTarget, DBPEvaluationContext.UI) + " doesn't support data manipulation");
                                }
                                containerMapping.setTarget((DBSDataManipulator) newTarget);
                                containerMapping.setMappingType(DatabaseMappingType.existing);
                                targetObject = (DBSDataManipulator) newTarget;
                                // ! Fall down is ok here
                            case existing:
                                for (DatabaseMappingAttribute attr : containerMapping.getAttributeMappings(monitor)) {
                                    if (attr.getMappingType() == DatabaseMappingType.create) {
                                        attr.updateMappingType(monitor);
                                        if (attr.getTarget() == null) {
                                            log.debug("Can't find target attribute '" + attr.getTargetName() + "' in '" + containerMapping.getTargetName() + "'");
                                        }
                                    }
                                }
                                break;
                        }
                    }
                }
            }
        } finally {
            monitor.done();
        }
    }

    private void createTargetTable(DBCSession session, DatabaseMappingContainer containerMapping) throws DBException {
        DBSObjectContainer schema = settings.getContainer();
        if (schema == null) {
            throw new DBException("No target container selected");
        }
        String sql = generateTargetTableDDL(session.getProgressMonitor(), session.getDataSource(), schema, containerMapping);
        try {
            executeDDL(session, sql);
        } catch (DBCException e) {
            throw new DBCException("Can't create target table:\n" + sql, e);
        }
    }

    public static String generateTargetTableDDL(DBRProgressMonitor monitor, DBPDataSource dataSource, DBSObjectContainer schema, DatabaseMappingContainer containerMapping) throws DBException {
        if (containerMapping.getMappingType() == DatabaseMappingType.skip) {
            return "";
        }
        monitor.subTask("Create table " + containerMapping.getTargetName());
        StringBuilder sql = new StringBuilder(500);
        if (!(dataSource instanceof SQLDataSource)) {
            throw new DBException("Data source doesn't support SQL");
        }
        SQLDataSource targetDataSource = (SQLDataSource) dataSource;

        String tableName = DBObjectNameCaseTransformer.transformName(targetDataSource, containerMapping.getTargetName());
        containerMapping.setTargetName(tableName);
        if (containerMapping.getMappingType() == DatabaseMappingType.create) {
            sql.append("CREATE TABLE ");
            if (schema instanceof DBSSchema || schema instanceof DBSCatalog) {
                sql.append(DBUtils.getQuotedIdentifier(schema));
                sql.append(targetDataSource.getSQLDialect().getCatalogSeparator());
            }
            sql.append(DBUtils.getQuotedIdentifier(targetDataSource, tableName)).append("(\n");
            Map<DBSAttributeBase, DatabaseMappingAttribute> mappedAttrs = new HashMap<>();
            for (DatabaseMappingAttribute attr : containerMapping.getAttributeMappings(monitor)) {
                if (attr.getMappingType() != DatabaseMappingType.create) {
                    continue;
                }
                if (!mappedAttrs.isEmpty()) sql.append(",\n");
                sql.append("\t");
                appendAttributeClause(dataSource, sql, attr);
                mappedAttrs.put(attr.getSource(), attr);
            }
            if (containerMapping.getSource() instanceof DBSEntity) {
                // Make primary key
                Collection<? extends DBSEntityAttribute> identifier = DBUtils.getBestTableIdentifier(monitor, (DBSEntity) containerMapping.getSource());
                if (!CommonUtils.isEmpty(identifier)) {
                    boolean idMapped = true;
                    for (DBSEntityAttribute idAttr : identifier) {
                        if (!mappedAttrs.containsKey(idAttr)) {
                            idMapped = false;
                            break;
                        }
                    }
                    if (idMapped) {
                        sql.append(",\n\tPRIMARY KEY (");
                        boolean hasAttr = false;
                        for (DBSEntityAttribute idAttr : identifier) {
                            DatabaseMappingAttribute mappedAttr = mappedAttrs.get(idAttr);
                            if (hasAttr) sql.append(",");
                            sql.append(DBUtils.getQuotedIdentifier(dataSource, mappedAttr.getTargetName()));
                            hasAttr = true;
                        }
                        sql.append(")\n");
                    }
                }
            }
            sql.append(")");
        } else {
            for (DatabaseMappingAttribute attr : containerMapping.getAttributeMappings(monitor)) {
                if (attr.getMappingType() == DatabaseMappingType.create) {
                    sql.append(generateTargetAttributeDDL(dataSource, attr)).append(";\n");
                }
            }
        }
        return sql.toString();
    }

    private static void appendAttributeClause(DBPDataSource dataSource, StringBuilder sql, DatabaseMappingAttribute attr) {
        sql.append(DBUtils.getQuotedIdentifier(dataSource, attr.getTargetName())).append(" ").append(attr.getTargetType(dataSource));
        if (SQLUtils.getDialectFromDataSource(dataSource).supportsNullability()) {
            if (attr.getSource().isRequired()) sql.append(" NOT NULL");
        }
    }

    private void createTargetAttribute(DBCSession session, DatabaseMappingAttribute attribute) throws DBCException {
        session.getProgressMonitor().subTask("Create column " + DBUtils.getObjectFullName(attribute.getParent().getTarget(), DBPEvaluationContext.DDL) + "." + attribute.getTargetName());
        String sql = generateTargetAttributeDDL(session.getDataSource(), attribute);
        try {
            executeDDL(session, sql);
        } catch (DBCException e) {
            throw new DBCException("Can't create target column:\n" + sql, e);
        }
    }

    @NotNull
    private static String generateTargetAttributeDDL(DBPDataSource dataSource, DatabaseMappingAttribute attribute) {
        StringBuilder sql = new StringBuilder(500);
        sql.append("ALTER TABLE ").append(DBUtils.getObjectFullName(attribute.getParent().getTarget(), DBPEvaluationContext.DDL))
            .append(" ADD ");
        appendAttributeClause(dataSource, sql, attribute);
        return sql.toString();
    }

    private void executeDDL(DBCSession session, String sql)
        throws DBCException {
        try (DBCStatement dbStat = DBUtils.makeStatement(session, sql, false)) {
            dbStat.executeStatement();
        }
        DBCTransactionManager txnManager = DBUtils.getTransactionManager(session.getExecutionContext());
        if (txnManager != null && !txnManager.isAutoCommit()) {
            // Commit DDL changes
            txnManager.commit(session);
        }
    }

    @Override
    public void finishTransfer(DBRProgressMonitor monitor, boolean last) {
        if (!last && settings.isOpenTableOnFinish()) {
            if (containerMapping != null && containerMapping.getTarget() != null) {
                DBWorkbench.getPlatformUI().openEntityEditor(containerMapping.getTarget());
            }
        }
    }

    public DBSDataManipulator getTargetObject() {
        return targetObject;
    }

    @Override
    public String getObjectName() {
        String targetName = null;
        if (targetObject != null) {
            targetName = DBUtils.getObjectFullName(targetObject, DBPEvaluationContext.UI);
        }
        if (settings == null) {
            return targetName;
        }

        if (targetName != null) {
            return targetName;
        }

        DatabaseMappingContainer dataMapping = settings.getDataMapping(sourceObject);
        if (dataMapping == null) {
            return "?";
        }

        targetName = dataMapping.getTargetName();

        switch (dataMapping.getMappingType()) {
            case create:
                return targetName + " [Create]";
            case existing:
                for (DatabaseMappingAttribute attr : dataMapping.getAttributeMappings(new VoidProgressMonitor())) {
                    if (attr.getMappingType() == DatabaseMappingType.create) {
                        return targetName + " [Alter]";
                    }
                }
                return targetName;// + " [No changes]";
            case skip:
                return "[Skip]";
            default:
                return "?";
        }
    }

    @Override
    public DBPImage getObjectIcon() {
        if (targetObject instanceof DBPImageProvider) {
            return DBValueFormatting.getObjectImage(targetObject);
        }
        return DBIcon.TREE_TABLE;
    }

    @Override
    public String getObjectContainerName() {
        DBPDataSourceContainer container = getDataSourceContainer();
        return container != null ? container.getName() : "?";
    }

    @Override
    public DBPImage getObjectContainerIcon() {
        DBPDataSourceContainer container = getDataSourceContainer();
        return container != null ? container.getDriver().getIcon() : null;
    }

    private DBPDataSourceContainer getDataSourceContainer() {
        if (targetObject != null) {
            return targetObject.getDataSource().getContainer();
        }
        DBSObjectContainer container = settings.getContainer();
        if (container != null) {
            return container.getDataSource().getContainer();
        }
        return null;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof DatabaseTransferConsumer &&
            CommonUtils.equalObjects(targetObject, ((DatabaseTransferConsumer) obj).targetObject);

    }

    public static class ObjectSerializer implements DBPObjectSerializer<DBTTask, DatabaseTransferConsumer> {

        @Override
        public void serializeObject(DatabaseTransferConsumer object, Map<String, Object> state) {
            DBSDataContainer dataContainer = object.targetObject;
            if (dataContainer instanceof DBSEntity) {
                state.put("type", "entity");
                if (dataContainer.getDataSource() != null) {
                    state.put("project", dataContainer.getDataSource().getContainer().getProject().getName());
                }
                state.put("entityId", DBUtils.getObjectFullId(dataContainer));
            } else {
                state.put("type", "unknown");
                log.error("Unsupported consumer data container: " + dataContainer);
            }
        }

        @Override
        public DatabaseTransferConsumer deserializeObject(DBRRunnableContext runnableContext, DBTTask objectContext, Map<String, Object> state) {
            DatabaseTransferConsumer consumer = new DatabaseTransferConsumer();
            try {
                runnableContext.run(false, true, monitor -> {
                    try {
                        String projectName = CommonUtils.toString(state.get("project"));
                        DBPProject project = CommonUtils.isEmpty(projectName) ? null : DBWorkbench.getPlatform().getWorkspace().getProject(projectName);
                        if (project == null) {
                            project = objectContext.getProject();
                        }
                        String id = CommonUtils.toString(state.get("entityId"));
                        consumer.targetObject = (DBSDataManipulator) DBUtils.findObjectById(monitor, project, id);
                    } catch (Exception e) {
                        throw new InvocationTargetException(e);
                    }
                });
            } catch (InvocationTargetException e) {
                log.debug("Error deserializing node location", e.getTargetException());
            } catch (InterruptedException e) {
                // Ignore
            }

            return consumer;
        }
    }

}
