package dev.museframework.test

import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.dsl.dsl.untilSuccessful
import dev.museframework.dsl.api.model.flow.FlowInitialState
import dev.museframework.engine.components.processor.dsl.logger
import dev.museframework.dsl.dsl.scheduler
import dev.museframework.dsl.annotation.MuseConfig
import jakarta.enterprise.context.ApplicationScoped

@MuseConfig
@ApplicationScoped
class UntilSuccessfulFlow : MuseConfigDefinition() {

    val untilSuccessfulDemoFlow = flow("until-successful-demo-flow") {
        initialState = FlowInitialState.STOPPED
        scheduler {
            docName = "UntilSuccessful Scheduler"
            fixedFrequency {

                frequency = "60000"

            }
        }
        setVariable("attemptCount") {
            docName = "Attempt Counter"
            value = "0"
        }
        setPayload {
            docName = "Initial Payload"
            value = "operation-input"
        }
        untilSuccessful {
            docName = "Retry Until Success"
            maxRetries = 3
            millisBetweenRetries = 1000
            logger {
                docName = "Retry Logger"
                message = "Attempt #[vars.attemptCount] - processing: #[payload]"
            }
            setVariable("attemptCount") {
                docName = "Increment Counter"
                value = "#[vars.attemptCount + 1]"
            }
        }
        logger {
            docName = "Success Logger"
            message = "Operation succeeded after #[vars.attemptCount] attempts"
        }
    }
}