package dev.museframework.objectstore.api.model

import dev.museframework.dsl.api.model.error.ErrorType

object ObjectStoreErrorType {
    val ANY = ErrorType("OS", "ANY", ErrorType.Mule.ANY)
    val INVALID_KEY = ErrorType("OS", "INVALID_KEY", ANY)
    val KEY_NOT_FOUND = ErrorType("OS", "KEY_NOT_FOUND", ANY)
    val KEY_ALREADY_EXISTS = ErrorType("OS", "KEY_ALREADY_EXISTS", ANY)
    val NULL_VALUE = ErrorType("OS", "NULL_VALUE", ANY)
    val STORE_NOT_AVAILABLE = ErrorType("OS", "STORE_NOT_AVAILABLE", ANY)
}
