// 文件: main/java/com/chaomixian/vflow/services/TimeTriggerReceiver.kt
package com.chaomixian.vflow.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.workflow.TriggerExecutionCoordinator
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.core.workflow.model.TriggerSpec
import com.chaomixian.vflow.core.workflow.module.triggers.IntervalTriggerModule
import com.chaomixian.vflow.core.workflow.module.triggers.TimeTriggerModule
import com.chaomixian.vflow.core.workflow.module.triggers.handlers.IntervalTriggerHandler
import com.chaomixian.vflow.core.workflow.module.triggers.handlers.TimeTriggerHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TimeTriggerReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TRIGGER = "com.chaomixian.vflow.ACTION_TIME_TRIGGER"
        const val EXTRA_WORKFLOW_ID = "workflow_id"
        const val EXTRA_TRIGGER_ID = "trigger_id"
        const val EXTRA_TRIGGER_STEP_ID = "trigger_step_id"
        private const val DEDUP_PREFS = "time_trigger_dedup"
        private const val DEDUP_WINDOW_MS = 60_000L
        private val dedupLock = Any()

        internal fun isDuplicateTimeTrigger(lastTriggeredAt: Long, now: Long): Boolean {
            return lastTriggeredAt > 0L && now - lastTriggeredAt in 0 until DEDUP_WINDOW_MS
        }

        private fun claimTimeTrigger(context: Context, triggerId: String, now: Long): Boolean {
            synchronized(dedupLock) {
                val prefs = context.getSharedPreferences(DEDUP_PREFS, Context.MODE_PRIVATE)
                val lastTriggeredAt = prefs.getLong(triggerId, 0L)
                if (isDuplicateTimeTrigger(lastTriggeredAt, now)) return false
                prefs.edit().putLong(triggerId, now).commit()
                return true
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TRIGGER) {
            val workflowId = intent.getStringExtra(EXTRA_WORKFLOW_ID)
            val triggerStepId = intent.getStringExtra(EXTRA_TRIGGER_STEP_ID)
                ?: intent.getStringExtra(EXTRA_TRIGGER_ID)
            if (workflowId == null || triggerStepId == null) return

            val pendingResult = goAsync()
            val scope = CoroutineScope(Dispatchers.IO)

            scope.launch {
                try {
                    val workflowManager = WorkflowManager(context.applicationContext)
                    val workflow = workflowManager.getWorkflow(workflowId)
                    val triggerStep = workflow?.getTrigger(triggerStepId)

                    if (workflow != null && triggerStep != null && workflow.isEnabled) {
                        val appContext = context.applicationContext
                        val resolvedTrigger = TriggerSpec(workflow, triggerStep)
                        if (triggerStep.moduleId == TimeTriggerModule().id &&
                            !claimTimeTrigger(appContext, resolvedTrigger.triggerId, System.currentTimeMillis())
                        ) {
                            DebugLogger.w(
                                "TimeTriggerReceiver",
                                "忽略 60 秒内重复送达的定时触发器: ${resolvedTrigger.triggerId}"
                            )
                            // 即使判定为重复送达（例如闹钟提前投递后重排到同一时刻），也必须重新排程，
                            // 否则“重排到同一天→再次送达→被去重跳过”会中断下一天的闹钟链条。
                            TimeTriggerHandler.rescheduleAlarm(appContext, resolvedTrigger)
                            return@launch
                        }
                        TriggerExecutionCoordinator.executeTrigger(
                            context = appContext,
                            trigger = resolvedTrigger
                        )

                        when (triggerStep.moduleId) {
                            TimeTriggerModule().id -> TimeTriggerHandler.rescheduleAlarm(appContext, resolvedTrigger)
                            IntervalTriggerModule().id -> IntervalTriggerHandler.rescheduleAlarm(appContext, resolvedTrigger)
                        }
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
