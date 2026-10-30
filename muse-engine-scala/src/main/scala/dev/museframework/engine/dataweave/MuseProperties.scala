package dev.museframework.engine.dataweave

import java.util.Properties

object MuseProperties {
  private val local = new ThreadLocal[Properties]

  def set(properties: Properties): Unit = local.set(properties)

  def current(): Properties = Option(local.get()).getOrElse(new Properties())

  def withProperties[T](properties: Properties, operation: java.util.function.Supplier[T]): T = {
    val previous = local.get()
    local.set(properties)
    try operation.get()
    finally if (previous == null) local.remove() else local.set(previous)
  }

  def clear(): Unit = local.remove()
}