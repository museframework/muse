package dev.museframework.test

import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.removeVariable
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.dsl.api.model.flow.FlowInitialState
import dev.museframework.engine.components.processor.dsl.logger
import dev.museframework.dsl.dsl.scheduler
import dev.museframework.dsl.annotation.MuseConfig
import jakarta.enterprise.context.ApplicationScoped

@MuseConfig
@ApplicationScoped
class RemoveVariableFlow : MuseConfigDefinition() {

    val removeVariableDemoFlow = flow("remove-variable-demo-flow") {
        initialState = FlowInitialState.STOPPED
        scheduler {
            docName = "RemoveVariable Scheduler"
            fixedFrequency {

                frequency = "30000"

            }
        }
        setVariable("tempVar1") {
            docName = "Temp Variable 1"
            value = "temporary-value-1"
        }
        setVariable("tempVar2") {
            docName = "Temp Variable 2"
            value = "temporary-value-2"
        }
        setVariable("persistentVar") {
            docName = "Persistent Variable"
            value = "this-should-remain"
        }
        logger {
            docName = "Before Removal"
            message = "tempVar1: #[vars.tempVar1], tempVar2: #[vars.tempVar2], persistentVar: #[vars.persistentVar]"
        }
        removeVariable("tempVar1") {
            docName = "Remove Temp Variable 1"
        }
        removeVariable("tempVar2") {
            docName = "Remove Temp Variable 2"
        }
        logger {
            docName = "After Removal"
            message = "tempVar1 removed, tempVar2 removed, persistentVar: #[vars.persistentVar]"
        }
        setPayload {
            docName = "Final Payload"
            value = "#[vars.persistentVar]"
        }
    }
}