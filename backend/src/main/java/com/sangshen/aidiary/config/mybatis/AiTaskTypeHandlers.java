package com.sangshen.aidiary.config.mybatis;

import com.sangshen.aidiary.entity.enums.AiTaskStatus;
import com.sangshen.aidiary.entity.enums.AiTaskTypes.SourceType;
import com.sangshen.aidiary.entity.enums.AiTaskTypes.TaskType;
import org.apache.ibatis.type.MappedTypes;

/**
 * Phase 3 三个枚举的 TypeHandler。
 *
 * <p>每个都只是给 {@link AbstractEnumNameTypeHandler} 传一个类型，
 * 真正的读写逻辑在基类里。这样写的好处：
 * <ul>
 *   <li>新增枚举时只要加一行内部类，不会有人"忘记写 TypeHandler"</li>
 *   <li>所有枚举的存取行为**必然一致**（都走 name）</li>
 * </ul>
 *
 * <p>⚠️ 这几个内部类必须在 {@code application.yml} 的
 * {@code mybatis.type-handlers-package} 指定的包下 ——
 * 由 {@code AiDiaryApplication} 上的 {@code @MapperScan} 同级的自动扫描注册。
 * 如果漏了注册，XML 里写全限定名的引用仍然有效
 * （MyBatis 会实例化它），但**反序列化时可能不生效** ——
 * 所以注册和显式引用这里都做了，是刻意的双保险。
 */
public final class AiTaskTypeHandlers {

    private AiTaskTypeHandlers() {
        // 纯容器类
    }

    /** {@code ai_task.status} 列。 */
    @MappedTypes(AiTaskStatus.class)
    public static class StatusHandler extends AbstractEnumNameTypeHandler<AiTaskStatus> {
        public StatusHandler() {
            super(AiTaskStatus.class);
        }
    }

    /** {@code ai_task.source_type} 列。 */
    @MappedTypes(SourceType.class)
    public static class SourceTypeHandler extends AbstractEnumNameTypeHandler<SourceType> {
        public SourceTypeHandler() {
            super(SourceType.class);
        }
    }

    /** {@code ai_task.task_type} 列。 */
    @MappedTypes(TaskType.class)
    public static class TaskTypeHandler extends AbstractEnumNameTypeHandler<TaskType> {
        public TaskTypeHandler() {
            super(TaskType.class);
        }
    }
}
