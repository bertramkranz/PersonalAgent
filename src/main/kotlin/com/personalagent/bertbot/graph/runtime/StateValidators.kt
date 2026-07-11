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
    override fun validate(value: BertBotState): ValidationResult {
        if (value.delegationPlan.isEmpty()) {
            return ValidationResult(isValid = true)
        }

        val selectedSubAgent = value.selectedSubAgent
        if (selectedSubAgent.isNullOrBlank()) {
            return ValidationResult(
                isValid = false,
                errors = listOf("Missing required field: selectedSubAgent"),
            )
        }

        return ValidationResult(isValid = true)
    }
}
