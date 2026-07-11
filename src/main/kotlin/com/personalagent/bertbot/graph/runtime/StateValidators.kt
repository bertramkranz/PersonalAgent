package com.personalagent.bertbot.graph.runtime

import com.personalagent.bertbot.graph.model.BertBotState

class RequiredFieldsStateValidator<T>(
    private val requiredFields: Map<String, (T) -> Any?>,
) : StateValidator<T> {
    override fun validate(value: T): ValidationResult {
        val missingFields =
            requiredFields.filter { (_, extractor) ->
                val extractedValue = extractor(value)
                when (extractedValue) {
                    null -> true
                    is String -> extractedValue.isBlank()
                    is Collection<*> -> extractedValue.isEmpty()
                    else -> false
                }
            }.keys

        return if (missingFields.isEmpty()) {
            ValidationResult(isValid = true)
        } else {
            ValidationResult(
                isValid = false,
                errors = missingFields.map { fieldName -> "Missing required field: $fieldName" },
            )
        }
    }
}

class DelegationToExecutorStateValidator : StateValidator<BertBotState> {
    private val delegate =
        RequiredFieldsStateValidator<BertBotState>(
            requiredFields =
                mapOf(
                    "delegationPlan" to { state -> state.delegationPlan },
                ),
        )

    override fun validate(value: BertBotState): ValidationResult = delegate.validate(value)
}
