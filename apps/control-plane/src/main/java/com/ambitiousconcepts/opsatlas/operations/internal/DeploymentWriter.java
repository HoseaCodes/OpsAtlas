package com.ambitiousconcepts.opsatlas.operations.internal;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The insert, in a transaction of its own.
 *
 * <p>This exists because of a bug worth recording rather than quietly fixing.
 * The idempotent write is "insert, and if the unique constraint fires, read back
 * the row that beat us". In PostgreSQL a constraint violation <strong>aborts the
 * whole transaction</strong>: every statement after it fails with "current
 * transaction is aborted, commands ignored until end of transaction block". So
 * catching the violation and reading in the same transaction cannot work - the
 * recovery read is exactly the statement that gets refused.
 *
 * <p>{@code REQUIRES_NEW} confines the failure to this method's own transaction,
 * which rolls back and ends. The caller's transaction is untouched and its
 * subsequent read runs normally.
 *
 * <p>{@code DeploymentLedgerIT} covers it: replaying a report failed with
 * exactly that PostgreSQL error before this class existed.
 */
@Component
class DeploymentWriter {

    private final DeploymentRepository deployments;

    DeploymentWriter(DeploymentRepository deployments) {
        this.deployments = deployments;
    }

    /**
     * @throws org.springframework.dao.DataIntegrityViolationException when this
     *     report has already been recorded. The caller reads back rather than
     *     treating it as an error.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    DeploymentEntity insert(DeploymentEntity entity) {
        return deployments.saveAndFlush(entity);
    }
}
