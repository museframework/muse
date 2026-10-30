package dev.museframework.engine.dataweave

import org.mule.weave.v2.runtime.{DataWeaveScriptingEngine, DynamicModuleComponentFactory, ParserConfiguration}
import org.mule.weave.v2.sdk.ClassLoaderWeaveResourceResolver
// in DataWeaveEngineFactory.scala — aggiungi temporaneamente

object DataWeaveEngineFactory {
  def create(parserConfig: ParserConfiguration): DataWeaveScriptingEngine = {
    val resolver = ClassLoaderWeaveResourceResolver.apply()

    // val nameId = NameIdentifier("dw::Muse::Runtime")
    // val path = NameIdentifierHelper.toWeaveFilePath(nameId, "/")
    // val url = getClass.getClassLoader.getResource(path.substring(1)) // rimuove il / iniziale

    val factory = DynamicModuleComponentFactory.apply(resolver, () => resolver, true)
    new DataWeaveScriptingEngine(factory, parserConfig)
  }
}