package dev.museframework.test

import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.parallelForEach
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.api.model.flow.FlowInitialState
import dev.museframework.engine.components.processor.dsl.logger
import dev.museframework.dsl.dsl.scheduler
import dev.museframework.dsl.annotation.MuseConfig
import jakarta.enterprise.context.ApplicationScoped

@MuseConfig
@ApplicationScoped
class ParallelForEachFlow : MuseConfigDefinition() {

    val parallelForEachDemoFlow = flow("parallel-foreach-demo-flow") {
        initialState = FlowInitialState.STOPPED
        scheduler {
            docName = "ParallelForEach Scheduler"
            fixedFrequency {

                frequency = "30000"

            }
        }
        setPayload {
            docName = "Set Collection"
            value = "#[['task1', 'task2', 'task3', 'task4', 'task5']]"
        }
        parallelForEach {
            docName = "Parallel Task Processing"
            collection = "#[payload]"
            timeout = 10000
            maxConcurrency = 3
            target = "processedTasks"
            logger {
                docName = "Task Logger"
                message = "Processing task: #[payload]"
            }
            setPayload {
                docName = "Transform Task"
                value = "#[payload ++ '-completed']"
            }
        }
        logger {
            docName = "Final Logger"
            message = "All tasks processed. Results in vars.processedTasks"
        }
    }
}