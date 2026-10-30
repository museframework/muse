package dev.museframework.engine.dataweave

import org.mule.weave.v2.model._
import org.mule.weave.v2.model.capabilities.UnknownLocationCapable
import org.mule.weave.v2.model.structure.{ArraySeq, ObjectSeq}
import org.mule.weave.v2.model.types.{ArrayType, ObjectType}
import org.mule.weave.v2.model.values.{ObjectValue, Value}
import org.mule.weave.v2.module.DataFormatManager
import org.mule.weave.v2.module.reader.{Reader, SourceProvider}
import org.mule.weave.v2.module.writer.WriterHelper
import org.mule.weave.v2.parser.ast.structure.DocumentNode
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.parser.location.UnknownLocation
import org.mule.weave.v2.parser.module.MimeType
import org.mule.weave.v2.parser.phase.{CompilationException, ParsingContext}
import org.mule.weave.v2.runtime._
import org.mule.weave.v2.sdk.{ClassLoaderWeaveResourceResolver, WeaveResource}
import scala.collection.JavaConverters._

object DataWeaveSplitter {
  case class Item(value: Any, mime: String, charset: java.nio.charset.Charset)
  val resolver = ClassLoaderWeaveResourceResolver()
  val factory = DynamicModuleComponentFactory(resolver, () => resolver, true)

  def compile(expression: String): ExecutableWeave[DocumentNode] = {
    val components = factory.createComponents()
    val parsing = ParsingContext(NameIdentifier.anonymous, components.parser, 1, false, components.componentManager)
    Seq("payload", "vars", "attributes", "correlationId", "error").foreach(parsing.addImplicitInput(_, None))
    parsing.addImplicitImport("dw::Muse::Runtime")
    val result = WeaveCompiler.compile(WeaveResource.anonymous(expression), parsing, components.compiler)
    if (!result.isSuccessful()) throw new CompilationException(result.messages())
    result.getResult().executable
  }

  def open(executable: ExecutableWeave[DocumentNode], bindings: ScriptingBindings): java.util.Iterator[Item] with AutoCloseable = {
    implicit val ctx: EvaluationContext = EvaluationContext(ServiceManager(new SPIWeaveServicesProvider()))
    try {
      val inputs = executable.declaredInputs()
      val readers: Map[String, Reader] = bindings.getBindings.asScala.map { case (name, value) =>
        val mime = inputs.get(name).map(_.defaultMimeType.toString)
          .getOrElse(if (value.getMimeType.isPresent) value.getMimeType.get else "application/java")
        val format = DataFormatManager.byContentType(mime).getOrElse(throw new IllegalArgumentException(mime))
        val reader = format.reader(SourceProvider(value.getValue, value.getCharset, Some(MimeType.fromSimpleString(mime))))
        val knownOptions = reader.settings.loadSettingsOptions().keys
        value.getProperties.asScala.foreach { case (key, setting) =>
          // MIME metadata such as profile is not a reader option; retain backend validation.
          knownOptions.find(_.equalsIgnoreCase(key)).foreach { canonical =>
            reader.setOption(UnknownLocation, canonical, setting)
          }
        }
        name -> reader
      }.toMap
      val value = executable.execute(readers, bindings.getLiteralValues.asScala.toMap)
      // Only the forward iterator escapes; never retain the root Value/ExecuteResult.
      val seq: Iterator[Value[_]] = if (ArrayType.accepts(value)) {
        value.evaluate.asInstanceOf[ArraySeq].toIterator()
      } else if (ObjectType.accepts(value)) {
        value.evaluate.asInstanceOf[ObjectSeq].toIterator.map(pair => ObjectValue(ObjectSeq(pair)))
      } else {
        ArrayType.coerce(value).evaluate.toIterator()
      }
      new java.util.Iterator[Item] with AutoCloseable {
        private var closed = false
        def guarded[A](f: => A): A = try f catch {
          case failure: Throwable =>
            try close() catch { case closing: Throwable => failure.addSuppressed(closing) }
            throw failure
        }
        override def hasNext: Boolean = if (closed) false else guarded {
          val more = seq.hasNext
          if (!more) close()
          more
        }
        override def next(): Item = guarded {
          if (closed) throw new java.util.NoSuchElementException()
          val current = seq.next()
          val parentServices = ctx.serviceManager
          val child = EvaluationContext(new ServiceManager(logger = parentServices.loggingService, serviceProvider = parentServices.serviceProvider))
          var primary: Throwable = null
          try {
            val mime = executable.outputDataFormatMimeType(child).getOrElse("application/java")
            val writer = DataFormatManager.byContentType(mime).get.writer(None, MimeType.fromSimpleString(mime))(child)
            executable.configureWriter(writer)(child)
            val written = WriterHelper.writeAndGetResult(writer, current, UnknownLocationCapable)(child)
            // Buffer only this element's resource-bearing content before disposing the child.
            val content = if (hasResources(written._1, new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]())) {
              materializeItem(written._1, new java.util.IdentityHashMap[AnyRef, AnyRef]())
            } else written._1
            Item(content, mime, written._2)
          } catch {
            case failure: Throwable => primary = failure; throw failure
          } finally {
            try child.close() catch {
              case cleanup: Throwable => if (primary == null) throw cleanup else if (cleanup ne primary) primary.addSuppressed(cleanup)
            }
          }
        }
        override def close(): Unit = if (!closed) { closed = true; ctx.close() }
      }
    } catch {
      case failure: Throwable =>
        try ctx.close() catch { case closing: Throwable => failure.addSuppressed(closing) }
        throw failure
    }
  }

  private def hasResources(value: Any, seen: java.util.IdentityHashMap[AnyRef, java.lang.Boolean]): Boolean = value match {
    case _: java.io.InputStream | _: java.util.Iterator[_] => true
    case values: java.util.Map[_, _] if seen.put(values, true) == null =>
      values.asScala.exists { case (_, item) => hasResources(item, seen) }
    case values: java.util.Collection[_] if seen.put(values, true) == null =>
      values.asScala.exists(item => hasResources(item, seen))
    case values: Array[AnyRef] if seen.put(values, true) == null =>
      values.exists(item => hasResources(item, seen))
    case _ => false
  }

  private def materializeItem(value: Any, seen: java.util.IdentityHashMap[AnyRef, AnyRef]): Any = value match {
    case null => null
    case ref: AnyRef if seen.containsKey(ref) => seen.get(ref)
    case stream: java.io.InputStream =>
      var primary: Throwable = null
      try {
        val bytes = stream.readAllBytes()
        seen.put(stream, bytes)
        bytes
      } catch { case failure: Throwable => primary = failure; throw failure }
      finally {
        try stream.close() catch {
          case cleanup: Throwable => if (primary == null) throw cleanup else if (cleanup ne primary) primary.addSuppressed(cleanup)
        }
      }
    case values: java.util.Map[_, _] =>
      val copy = new java.util.LinkedHashMap[Any, Any]()
      seen.put(values, copy)
      values.asScala.foreach { case (key, item) => copy.put(key, materializeItem(item, seen)) }
      copy
    case values: java.util.Collection[_] =>
      val copy = new java.util.ArrayList[Any](values.size())
      seen.put(values, copy)
      values.asScala.foreach(item => copy.add(materializeItem(item, seen)))
      copy
    case values: Array[AnyRef] =>
      val copy = values.clone()
      seen.put(values, copy)
      values.indices.foreach(index => copy(index) = materializeItem(values(index), seen).asInstanceOf[AnyRef])
      copy
    case values: java.util.Iterator[_] =>
      val copy = new java.util.ArrayList[Any]()
      seen.put(values, copy)
      var primary: Throwable = null
      try { while (values.hasNext) copy.add(materializeItem(values.next(), seen)); copy }
      catch { case failure: Throwable => primary = failure; throw failure }
      finally values match {
        case closeable: org.mule.weave.v2.module.pojo.writer.JavaWriterIterator if !closeable.closed =>
          try closeable.close() catch {
            case cleanup: Throwable => if (primary == null) throw cleanup else if (cleanup ne primary) primary.addSuppressed(cleanup)
          }
        case _ =>
      }
    case _ => value
  }
}
