package dev.museframework.test

import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.forEach
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.dsl.api.model.flow.FlowInitialState
import dev.museframework.engine.components.processor.dsl.logger
import dev.museframework.dsl.dsl.scheduler
import dev.museframework.dsl.annotation.MuseConfig
import jakarta.enterprise.context.ApplicationScoped

@MuseConfig
@ApplicationScoped
class ForEachFlow : MuseConfigDefinition() {

    val forEachDemoFlow = flow("foreach-demo-flow") {
        initialState = FlowInitialState.STOPPED
        scheduler {
            docName = "ForEach Scheduler"
            fixedFrequency {

                frequency = "30000"

            }
        }
        setPayload {
            docName = "Set Collection"
            value = "#[['apple', 'banana', 'orange', 'grape']]"
        }
        forEach {
            docName = "Process Each Fruit"
            collection = "#[payload]"
            batchSize = 2
            setVariable("currentFruit") {
                docName = "Current Item"
                value = "#[payload]"
            }
            logger {
                docName = "Fruit Logger"
                message = "Processing fruit: #[vars.currentFruit], counter: #[vars.counter]"
            }
            setPayload {
                docName = "Transform Fruit"
                value = "#[(payload joinBy \",\") ++ '-processed']"
            }
        }
        logger {
            docName = "Final Logger"
            message = "ForEach completed. Root message: #[vars.rootMessage]"
        }
    }
}