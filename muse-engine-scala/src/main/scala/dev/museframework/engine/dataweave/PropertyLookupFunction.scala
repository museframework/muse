package dev.museframework.engine.dataweave

import org.mule.weave.v2.core.functions.UnaryFunctionValue
import org.mule.weave.v2.model.EvaluationContext
import org.mule.weave.v2.model.types.StringType
import org.mule.weave.v2.model.values.{StringValue, Value}

class PropertyLookupFunction extends UnaryFunctionValue {

  override val R = StringType

  override protected def doExecute(v: R.V)(implicit ctx: EvaluationContext): Value[_] = {
    val key = v.evaluate.toString
    val value = MuseProperties.current().getProperty(key)
    if (value == null) throw new IllegalArgumentException(s"Property '$key' not found")
    StringValue(value)
  }
}
