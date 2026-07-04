package com.ledgerflow.domain.model

sealed interface Result<out T> {
    data class Success<out T>(val data: T) : Result<T>
    sealed interface Failure : Result<Nothing> {
        data class DatabaseError(val exception: Throwable) : Failure
        data class ValidationError(val message: String) : Failure
        data class SecurityError(val reason: String) : Failure
        object NetworkError : Failure
    }
}
