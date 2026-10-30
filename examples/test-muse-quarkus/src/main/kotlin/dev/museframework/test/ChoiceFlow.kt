package dev.museframework.test

import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.dsl.choice
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.dsl.api.model.flow.FlowInitialState
import dev.museframework.engine.components.processor.dsl.logger
import dev.museframework.dsl.dsl.scheduler
import dev.museframework.dsl.annotation.MuseConfig
import jakarta.enterprise.context.ApplicationScoped

@MuseConfig
@ApplicationScoped
class ChoiceFlow : MuseConfigDefinition() {

    val choiceDemoFlow = flow("choice-demo-flow") {
        initialState = FlowInitialState.STOPPED
        scheduler {
            docName = "Choice Scheduler"
            fixedFrequency {

                frequency = "30000"

            }
        }
        setVariable("status") {
            docName = "Set Status Variable"
            value = "success"
        }
        choice {
            docName = "Status Router"
            `when`("#[vars.status == 'success']") {
                logger {
                    docName = "Success Logger"
                    message = "Status is success!"
                }
                setPayload {
                    docName = "Success Payload"
                    value = "SUCCESS_RESULT"
                }
            }
            `when`("#[vars.status == 'error']") {
                logger {
                    docName = "Error Logger"
                    message = "Status is error!"
                }
                setPayload {
                    docName = "Error Payload"
                    value = "ERROR_RESULT"
                }
            }
            otherwise {
                logger {
                    docName = "Default Logger"
                    message = "Unknown status"
                }
                setPayload {
                    docName = "Default Payload"
                    value = "UNKNOWN_RESULT"
                }
            }
        }
        logger {
            docName = "Final Logger"
            message = "Choice result: #[payload]"
        }
    }
}