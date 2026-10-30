package dev.museframework.engine.dataweave

import org.mule.weave.v2.model.values._

class MuseRuntimeModule extends NativeValueProvider {

  override def name(): String = "MuseRuntime"

  override def getNativeFunction(name: String): Option[FunctionValue] = name match {
    case "p" => Some(new PropertyLookupFunction())
    case _ => None
  }
}
