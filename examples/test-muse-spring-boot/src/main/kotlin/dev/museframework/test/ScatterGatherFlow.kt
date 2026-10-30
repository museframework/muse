package dev.museframework.test

import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.scatterGather
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.dsl.api.model.flow.FlowInitialState
import dev.museframework.engine.components.processor.dsl.logger
import dev.museframework.dsl.dsl.scheduler
import dev.museframework.springboot.MuseConfigComponent

@MuseConfigComponent
class ScatterGatherFlow : MuseConfigDefinition() {

    val scatterGatherDemoFlow = flow("scatter-gather-demo-flow") {
        initialState = FlowInitialState.STOPPED
        scheduler {
            docName = "ScatterGather Scheduler"
            fixedFrequency {

                frequency = "30000"

            }
        }
        setVariable("sharedVar") {
            docName = "Initial Shared Var"
            value = "initial-value"
        }
        scatterGather {
            docName = "Parallel Routes"
            timeout = 5000
            maxConcurrency = 2
            route {
                setPayload {
                    docName = "Route 1 Payload"
                    value = "route-1-result"
                }
                setVariable("routeVar") {
                    docName = "Route 1 Variable"
                    value = "from-route-1"
                }
                logger {
                    docName = "Route 1 Logger"
                    message = "Route 1 executed"
                }
            }
            route {
                setPayload {
                    docName = "Route 2 Payload"
                    value = "route-2-result"
                }
                setVariable("routeVar") {
                    docName = "Route 2 Variable"
                    value = "from-route-2"
                }
                logger {
                    docName = "Route 2 Logger"
                    message = "Route 2 executed"
                }
            }
            route {
                setPayload {
                    docName = "Route 3 Payload"
                    value = "route-3-result"
                }
                logger {
                    docName = "Route 3 Logger"
                    message = "Route 3 executed"
                }
            }
        }
        logger {
            docName = "Aggregated Results Logger"
            message = "ScatterGather results: #[payload]"
        }
    }
}