/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.tm.api;

import java.util.List;

import io.seata.common.exception.ShouldNeverHappenException;
import io.seata.core.context.GlobalLockConfigHolder;
import io.seata.core.exception.TransactionException;
import io.seata.core.model.GlobalLockConfig;
import io.seata.core.model.GlobalStatus;
import io.seata.tm.api.transaction.Propagation;
import io.seata.tm.api.transaction.SuspendedResourcesHolder;
import io.seata.tm.api.transaction.TransactionHook;
import io.seata.tm.api.transaction.TransactionHookManager;
import io.seata.tm.api.transaction.TransactionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Template of executing business logic with a global transaction.
 *
 * @author sharajava
 */
public class TransactionalTemplate {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionalTemplate.class);


    /**
     * Execute object.
     *
     * @param business the business
     * @return the object
     * @throws TransactionalExecutor.ExecutionException the execution exception
     */
    public Object execute(TransactionalExecutor business) throws Throwable {
        // 1. Get transactionInfo
        // 获取事务信息
        TransactionInfo txInfo = business.getTransactionInfo();
        if (txInfo == null) {
            throw new ShouldNeverHappenException("transactionInfo does not exist");
        }
        // 1.1 Get current transaction, if not null, the tx role is 'GlobalTransactionRole.Participant'.
        // 获取当前事务，主要获取Xid
        GlobalTransaction tx = GlobalTransactionContext.getCurrent();

        // 1.2 Handle the transaction propagation.
        // 根据配置的不同事务传播行为，执行不同的逻辑
        Propagation propagation = txInfo.getPropagation();
        SuspendedResourcesHolder suspendedResourcesHolder = null;
        try {
            switch (propagation) {
                case NOT_SUPPORTED:
                    // If transaction is existing, suspend it.
                    if (existingTransaction(tx)) {
                        suspendedResourcesHolder = tx.suspend();
                    }
                    // Execute without transaction and return.
                    return business.execute();
                case REQUIRES_NEW:
                    // If transaction is existing, suspend it, and then begin new transaction.
                    if (existingTransaction(tx)) {
                        suspendedResourcesHolder = tx.suspend();
                        tx = GlobalTransactionContext.createNew();
                    }
                    // Continue and execute with new transaction
                    break;
                case SUPPORTS:
                    // If transaction is not existing, execute without transaction.
                    if (notExistingTransaction(tx)) {
                        return business.execute();
                    }
                    // Continue and execute with new transaction
                    break;
                case REQUIRED:
                    // If current transaction is existing, execute with current transaction,
                    // else continue and execute with new transaction.
                    break;
                case NEVER:
                    // If transaction is existing, throw exception.
                    if (existingTransaction(tx)) {
                        throw new TransactionException(
                            String.format("Existing transaction found for transaction marked with propagation 'never', xid = %s"
                                    , tx.getXid()));
                    } else {
                        // Execute without transaction and return.
                        return business.execute();
                    }
                case MANDATORY:
                    // If transaction is not existing, throw exception.
                    if (notExistingTransaction(tx)) {
                        throw new TransactionException("No existing transaction found for transaction marked with propagation 'mandatory'");
                    }
                    // Continue and execute with current transaction.
                    break;
                default:
                    throw new TransactionException("Not Supported Propagation:" + propagation);
            }

            // 1.3 If null, create new transaction with role 'GlobalTransactionRole.Launcher'.
            // 当前没有事务，则创建一个新的事务
            if (tx == null) {
                tx = GlobalTransactionContext.createNew();
            }

            // set current tx config to holder
            GlobalLockConfig previousConfig = replaceGlobalLockConfig(txInfo);

            try {
                // 2. If the tx role is 'GlobalTransactionRole.Launcher', send the request of beginTransaction to TC,
                //    else do nothing. Of course, the hooks will still be triggered.
                // 开始执行全局事务
                beginTransaction(txInfo, tx);

                Object rs;
                try {
                    // Do Your Business
                    // 执行当前业务逻辑：
                    // 1. 在TC注册当前分支事务，TC会在branch_table中插入一条分支事务数据
                    // 2. 执行本地update语句，并在执行前后查询数据状态，并把数据前后镜像存入到undo_log表中
                    // 3. 远程调用其他应用，远程应用接收到xid，也会注册分支事务，写入branch_table及本地undo_log表
                    // 4. 会在lock_table表中插入全局锁数据（一个分支一条）
                    rs = business.execute();
                } catch (Throwable ex) {
                    // 3. The needed business exception to rollback.
                    // 发生异常全局回滚，各个数据通过undo_log表进行事务补偿
                    completeTransactionAfterThrowing(txInfo, tx, ex);
                    throw ex;
                }

                // 4. everything is fine, commit.
                // 全局提交事务
                commitTransaction(tx);

                return rs;
            } finally {
                //5. clear
                // 清除所有资源
                resumeGlobalLockConfig(previousConfig);
                triggerAfterCompletion();
                cleanUp();
            }
        } finally {
            // If the transaction is suspended, resume it.
            if (suspendedResourcesHolder != null) {
                tx.resume(suspendedResourcesHolder);
            }
        }
    }

    private boolean existingTransaction(GlobalTransaction tx) {
        return tx != null;
    }

    private boolean notExistingTransaction(GlobalTransaction tx) {
        return tx == null;
    }

    private GlobalLockConfig replaceGlobalLockConfig(TransactionInfo info) {
        GlobalLockConfig myConfig = new GlobalLockConfig();
        myConfig.setLockRetryInternal(info.getLockRetryInternal());
        myConfig.setLockRetryTimes(info.getLockRetryTimes());
        return GlobalLockConfigHolder.setAndReturnPrevious(myConfig);
    }

    private void resumeGlobalLockConfig(GlobalLockConfig config) {
        if (config != null) {
            GlobalLockConfigHolder.setAndReturnPrevious(config);
        } else {
            GlobalLockConfigHolder.remove();
        }
    }

    private void completeTransactionAfterThrowing(TransactionInfo txInfo, GlobalTransaction tx, Throwable originalException) throws TransactionalExecutor.ExecutionException {
        //roll back
        if (txInfo != null && txInfo.rollbackOn(originalException)) {
            try {
                rollbackTransaction(tx, originalException);
            } catch (TransactionException txe) {
                // Failed to rollback
                throw new TransactionalExecutor.ExecutionException(tx, txe,
                        TransactionalExecutor.Code.RollbackFailure, originalException);
            }
        } else {
            // not roll back on this exception, so commit
            commitTransaction(tx);
        }
    }

    private void commitTransaction(GlobalTransaction tx) throws TransactionalExecutor.ExecutionException {
        try {
            triggerBeforeCommit();
            tx.commit();
            triggerAfterCommit();
        } catch (TransactionException txe) {
            // 4.1 Failed to commit
            throw new TransactionalExecutor.ExecutionException(tx, txe,
                TransactionalExecutor.Code.CommitFailure);
        }
    }

    private void rollbackTransaction(GlobalTransaction tx, Throwable originalException) throws TransactionException, TransactionalExecutor.ExecutionException {
        triggerBeforeRollback();
        tx.rollback();
        triggerAfterRollback();
        // 3.1 Successfully rolled back
        throw new TransactionalExecutor.ExecutionException(tx, GlobalStatus.RollbackRetrying.equals(tx.getLocalStatus())
            ? TransactionalExecutor.Code.RollbackRetrying : TransactionalExecutor.Code.RollbackDone, originalException);
    }
    /**
     *向TC发起请求，这里采用了模板模式
     */
    private void beginTransaction(TransactionInfo txInfo, GlobalTransaction tx) throws TransactionalExecutor.ExecutionException {
        try {
            triggerBeforeBegin();
            // 对TC发起请求
            tx.begin(txInfo.getTimeOut(), txInfo.getName());
            triggerAfterBegin();
        } catch (TransactionException txe) {
            throw new TransactionalExecutor.ExecutionException(tx, txe,
                TransactionalExecutor.Code.BeginFailure);

        }
    }

    private void triggerBeforeBegin() {
        for (TransactionHook hook : getCurrentHooks()) {
            try {
                hook.beforeBegin();
            } catch (Exception e) {
                LOGGER.error("Failed execute beforeBegin in hook {}", e.getMessage(), e);
            }
        }
    }

    private void triggerAfterBegin() {
        for (TransactionHook hook : getCurrentHooks()) {
            try {
                hook.afterBegin();
            } catch (Exception e) {
                LOGGER.error("Failed execute afterBegin in hook {}", e.getMessage(), e);
            }
        }
    }

    private void triggerBeforeRollback() {
        for (TransactionHook hook : getCurrentHooks()) {
            try {
                hook.beforeRollback();
            } catch (Exception e) {
                LOGGER.error("Failed execute beforeRollback in hook {}", e.getMessage(), e);
            }
        }
    }

    private void triggerAfterRollback() {
        for (TransactionHook hook : getCurrentHooks()) {
            try {
                hook.afterRollback();
            } catch (Exception e) {
                LOGGER.error("Failed execute afterRollback in hook {}", e.getMessage(), e);
            }
        }
    }

    private void triggerBeforeCommit() {
        for (TransactionHook hook : getCurrentHooks()) {
            try {
                hook.beforeCommit();
            } catch (Exception e) {
                LOGGER.error("Failed execute beforeCommit in hook {}", e.getMessage(), e);
            }
        }
    }

    private void triggerAfterCommit() {
        for (TransactionHook hook : getCurrentHooks()) {
            try {
                hook.afterCommit();
            } catch (Exception e) {
                LOGGER.error("Failed execute afterCommit in hook {}", e.getMessage(), e);
            }
        }
    }

    private void triggerAfterCompletion() {
        for (TransactionHook hook : getCurrentHooks()) {
            try {
                hook.afterCompletion();
            } catch (Exception e) {
                LOGGER.error("Failed execute afterCompletion in hook {}", e.getMessage(), e);
            }
        }
    }

    private void cleanUp() {
        TransactionHookManager.clear();
    }

    private List<TransactionHook> getCurrentHooks() {
        return TransactionHookManager.getHooks();
    }
}
