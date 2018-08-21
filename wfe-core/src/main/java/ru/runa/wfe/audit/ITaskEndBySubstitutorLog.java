package ru.runa.wfe.audit;

import javax.persistence.Transient;

public interface ITaskEndBySubstitutorLog extends ITaskEndLog {

    @Override
    @Transient
    default Type getType() {
        return Type.TASK_END_BY_SUBSTITUTOR;
    }

    @Override
    default void processBy(ProcessLogVisitor visitor) {
        visitor.onTaskEndBySubstitutorLog(this);
    }
}