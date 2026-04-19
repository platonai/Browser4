# Validation Rules Template

## Built-in Rules

### Email Validation
```json
{
  "rules": ["email"],
  "data": {
    "email": "user@example.com"
  }
}
```

### Required Fields
```json
{
  "rules": ["required"],
  "data": {
    "field1": "value1",
    "field2": "value2"
  }
}
```

## Custom Rule Examples

### Phone Number Validation
```kotlin
"phone" -> {
    val phone = data["phone"] as? String
    val isValid = phone?.matches(Regex("^\\+?[1-9]\\d{1,14}$")) ?: false
    validationResults[rule] = isValid
    if (!isValid) errors.add("Invalid phone format")
}
```

### URL Validation
```kotlin
"url" -> {
    val url = data["url"] as? String
    val isValid = url?.matches(Regex("^https?://.*")) ?: false
    validationResults[rule] = isValid
    if (!isValid) errors.add("Invalid URL format")
}
```

### Age Range Validation
```kotlin
"age_range" -> {
    val age = data["age"]?.toString()?.toIntOrNull()
    val isValid = age != null && age in 18..120
    validationResults[rule] = isValid
    if (!isValid) errors.add("Age must be between 18 and 120")
}
```

### Password Strength
```kotlin
"password_strength" -> {
    val password = data["password"] as? String
    val hasMinLength = password != null && password.length >= 8
    val hasUpperCase = password?.any { it.isUpperCase() } ?: false
    val hasLowerCase = password?.any { it.isLowerCase() } ?: false
    val hasDigit = password?.any { it.isDigit() } ?: false
    val hasSpecial = password?.any { !it.isLetterOrDigit() } ?: false
    
    val isValid = hasMinLength && hasUpperCase && hasLowerCase && hasDigit && hasSpecial
    validationResults[rule] = isValid
    if (!isValid) errors.add("Password must be at least 8 characters with uppercase, lowercase, digit, and special character")
}
```

### Credit Card Validation
```kotlin
"credit_card" -> {
    val cardNumber = data["creditCard"] as? String?.replace(Regex("\\s"), "")
    val isValid = cardNumber?.matches(Regex("^\\d{13,19}$")) ?: false
    validationResults[rule] = isValid
    if (!isValid) errors.add("Invalid credit card number")
}
```

### Date Format Validation
```kotlin
"date_format" -> {
    val date = data["date"] as? String
    val isValid = try {
        date?.let {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            LocalDate.parse(it, formatter)
            true
        } ?: false
    } catch (e: Exception) {
        false
    }
    validationResults[rule] = isValid
    if (!isValid) errors.add("Invalid date format (expected yyyy-MM-dd)")
}
```

## Validation Patterns

### Sequential Validation
```kotlin
val rules = listOf("required", "email", "format")
for (rule in rules) {
    val result = validate(data, rule)
    if (!result.success) {
        return result // Stop on first failure
    }
}
```

### Parallel Validation
```kotlin
val rules = listOf("email", "phone", "url")
val results = rules.map { rule ->
    async { validate(data, rule) }
}.awaitAll()
```

### Conditional Validation
```kotlin
if (data.containsKey("email")) {
    validate(data, "email")
}
if (data.containsKey("phone")) {
    validate(data, "phone")
}
```

## Usage Examples

### Single Rule
```kotlin
val result = registry.execute(
    skillId = "data-validation",
    context = context,
    params = mapOf(
        "data" to mapOf("email" to "test@example.com"),
        "rules" to listOf("email")
    )
)
```

### Multiple Rules
```kotlin
val result = registry.execute(
    skillId = "data-validation",
    context = context,
    params = mapOf(
        "data" to formData,
        "rules" to listOf("required", "email", "phone")
    )
)
```

### With Custom Error Handling
```kotlin
val result = validate(data, rules)
if (!result.success) {
    val errors = result.metadata["errors"] as List<String>
    errors.forEach { error ->
        logger.error("Validation error: $error")
    }
}
```
