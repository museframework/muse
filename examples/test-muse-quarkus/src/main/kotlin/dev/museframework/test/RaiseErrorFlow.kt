package dev.museframework.test

import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.dsl.*
import dev.museframework.dsl.api.model.flow.FlowInitialState
import dev.museframework.engine.components.processor.dsl.logger
import dev.museframework.dsl.dsl.scheduler
import dev.museframework.dsl.annotation.MuseConfig
import jakarta.enterprise.context.ApplicationScoped

@MuseConfig
@ApplicationScoped
class RaiseErrorFlow : MuseConfigDefinition() {

    val raiseErrorDemoFlow = flow("raise-error-demo-flow") {
        initialState = FlowInitialState.STOPPED
        scheduler {
            docName = "RaiseError Scheduler"
            fixedFrequency {

                frequency = "30000"

            }
        }
        setVariable("inputValue") {
            docName = "Input Value"
            value = "-5"
        }
        choice {
            docName = "Validate Input"
            `when`("#[vars.inputValue < 0]") {
                raiseError {
                    docName = "Invalid Input Error"
                    type = "VALIDATION:NEGATIVE_VALUE"
                    description = "Input value cannot be negative: #[vars.inputValue]"
                }
            }
            otherwise {
                logger {
                    docName = "Valid Input"
                    message = "Processing valid input: #[vars.inputValue]"
                }
                setPayload {
                    docName = "Result Payload"
                    value = "#[vars.inputValue * 2]"
                }
            }
        }
        logger {
            docName = "Final Logger"
            message = "Processing complete. Result: #[payload]"
        }
    }
}