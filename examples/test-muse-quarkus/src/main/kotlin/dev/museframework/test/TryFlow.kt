package dev.museframework.test

import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.dsl.*
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.api.model.flow.FlowInitialState
import dev.museframework.engine.components.processor.dsl.logger
import dev.museframework.dsl.dsl.scheduler
import dev.museframework.dsl.annotation.MuseConfig
import jakarta.enterprise.context.ApplicationScoped

@MuseConfig
@ApplicationScoped
class TryFlow : MuseConfigDefinition() {

    val tryDemoFlow = flow("try-demo-flow") {
        initialState = FlowInitialState.STOPPED
        scheduler {
            docName = "Try Scheduler"
            fixedFrequency {

                frequency = "30000"

            }
        }
        setPayload {
            docName = "Initial Payload"
            value = "initial-value"
        }
        `try` {
            docName = "Protected Operations"
            setVariable("shouldFail") {
                docName = "Failure Flag"
                value = "false"
            }
            choice {
                docName = "Conditional Error"
                `when`("#[vars.shouldFail == 'true']") {
                    raiseError {
                        docName = "Intentional Error"
                        type = "APP:DEMO_ERROR"
                        description = "Demo error from Try scope"
                    }
                }
                otherwise {
                    logger {
                        docName = "Normal Path"
                        message = "No error raised"
                    }
                }
            }
            errorHandler {
                onErrorContinue(ErrorType("APP", "DEMO_ERROR")) {
                    logger {
                        docName = "Error Logger"
                        message = "Caught error: #[error.description]"
                    }
                    setPayload {
                        docName = "Error Recovery Payload"
                        value = "recovered-value"
                    }
                }
            }
        }
        logger {
            docName = "After Try"
            message = "Try completed. Payload: #[payload]"
        }
    }
}