# Form Data Template

## Contact Form

```json
{
  "name": "John Doe",
  "email": "john@example.com",
  "phone": "555-0123",
  "message": "Your message here"
}
```

## Sign-up Form

```json
{
  "username": "johndoe",
  "email": "john@example.com",
  "password": "SecurePassword123!",
  "confirmPassword": "SecurePassword123!",
  "firstName": "John",
  "lastName": "Doe",
  "agreeToTerms": "true"
}
```

## Shipping Address Form

```json
{
  "firstName": "John",
  "lastName": "Doe",
  "address": "123 Main Street",
  "address2": "Apt 4B",
  "city": "Anytown",
  "state": "CA",
  "zip": "12345",
  "country": "USA",
  "phone": "555-0123"
}
```

## Job Application Form

```json
{
  "firstName": "John",
  "lastName": "Doe",
  "email": "john@example.com",
  "phone": "555-0123",
  "resume": "/path/to/resume.pdf",
  "coverLetter": "I am interested in...",
  "linkedin": "https://linkedin.com/in/johndoe",
  "yearsExperience": "5",
  "position": "Software Engineer",
  "availableDate": "2024-02-01"
}
```

## Survey Form

```json
{
  "age": "25-34",
  "gender": "prefer-not-to-say",
  "satisfaction": "5",
  "wouldRecommend": "yes",
  "comments": "Great service!",
  "newsletter": "true"
}
```

## Profile Update Form

```json
{
  "displayName": "John Doe",
  "bio": "Software developer passionate about web automation",
  "website": "https://johndoe.com",
  "twitter": "@johndoe",
  "github": "johndoe",
  "location": "San Francisco, CA",
  "company": "Acme Corp"
}
```

## Usage Example

```kotlin
val formData = mapOf(
    "name" to "John Doe",
    "email" to "john@example.com",
    "phone" to "555-0123",
    "message" to "Hello from Browser4!"
)

val result = registry.execute(
    skillId = "form-filling",
    context = context,
    params = mapOf(
        "url" to "https://example.com/contact",
        "formData" to formData
    )
)
```

## Field Name Conventions

Common variations for field names:

- **Email**: `email`, `e-mail`, `mail`, `email-address`
- **Phone**: `phone`, `telephone`, `tel`, `phone-number`
- **First Name**: `firstname`, `first_name`, `fname`, `given-name`
- **Last Name**: `lastname`, `last_name`, `lname`, `family-name`
- **Address**: `address`, `street`, `street-address`
- **Zip Code**: `zip`, `zipcode`, `postal-code`, `postcode`
