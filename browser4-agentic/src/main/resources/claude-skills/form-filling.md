# Form Filling Skill

Automatically fill and submit web forms using Browser4's browser automation capabilities.

## When to Use This Skill

Use this skill when you need to:
- Fill out registration or login forms
- Submit search queries
- Complete multi-step forms or wizards
- Automate form submissions with test data
- Fill forms with user-provided data

## How to Use This Skill

### Step 1: Navigate and Identify the Form

1. **Navigate to the page** containing the form
2. **Wait for the form to load** completely
3. **Identify form fields** using CSS selectors or field names
4. **Note the field types**: text inputs, checkboxes, radio buttons, dropdowns, etc.

### Step 2: Prepare the Data

Organize the data you'll fill into the form:
- Match data keys to form field identifiers
- Ensure data types are appropriate for each field
- Validate required fields are provided
- Handle default values for optional fields

### Step 3: Fill Each Field

For each form field:
1. **Locate the field** using a CSS selector
2. **Wait for the field** to be ready (visible and enabled)
3. **Fill the field** with appropriate data using the correct method for the field type

### Step 4: Submit the Form (Optional)

After filling all fields:
1. **Locate the submit button** (usually `button[type="submit"]` or `input[type="submit"]`)
2. **Click the button** to submit
3. **Wait for response** (new page load or AJAX response)
4. **Verify success** by checking for success messages or new content

## Field Types and How to Fill Them

### Text Input Fields
**Element**: `<input type="text">`, `<input type="email">`, `<input type="password">`, `<textarea>`

**How to fill**:
- Use `browser.fill(selector, value)` or `browser.type(selector, value)`
- `fill` is faster (sets value directly)
- `type` simulates keyboard typing (triggers keystroke events)

**Example**:
```
browser.fill("input[name='username']", "john_doe")
browser.fill("input[type='email']", "john@example.com")
browser.fill("textarea[name='message']", "Hello, this is my message")
```

### Password Fields
**Element**: `<input type="password">`

**How to fill**:
- Same as text fields, but content is masked
- Ensure secure handling of password data

**Example**:
```
browser.fill("input[type='password']", "SecurePassword123")
```

### Checkboxes
**Element**: `<input type="checkbox">`

**How to fill**:
- Use `browser.check(selector)` to check
- Use `browser.uncheck(selector)` to uncheck
- Or use `browser.click(selector)` to toggle

**Example**:
```
browser.check("input[name='subscribe']")
browser.check("input#terms-agreement")
```

### Radio Buttons
**Element**: `<input type="radio">`

**How to fill**:
- Use `browser.click(selector)` to select
- Only one in a group can be selected at a time

**Example**:
```
browser.click("input[name='gender'][value='male']")
browser.click("#payment-method-credit")
```

### Dropdown Menus (Select)
**Element**: `<select>`

**How to fill**:
- Use `browser.select(selector, value)` to select by value
- Or `browser.select_by_text(selector, text)` to select by visible text

**Example**:
```
browser.select("select[name='country']", "US")
browser.select_by_text("select[name='country']", "United States")
```

### File Upload
**Element**: `<input type="file">`

**How to fill**:
- Use `browser.upload_file(selector, file_path)`
- Provide absolute path to the file

**Example**:
```
browser.upload_file("input[type='file']", "/path/to/document.pdf")
```

### Date Fields
**Element**: `<input type="date">`

**How to fill**:
- Use `browser.fill(selector, date_string)`
- Format: "YYYY-MM-DD"

**Example**:
```
browser.fill("input[type='date']", "2024-12-31")
```

## Examples

### Example 1: Simple Contact Form

**Form Structure**:
- Name (text)
- Email (email)
- Message (textarea)
- Submit button

**Steps**:
```
1. Navigate to contact page
browser.navigate("https://example.com/contact")

2. Wait for form to load
browser.wait_for_element("form#contact-form")

3. Fill name field
browser.fill("input[name='name']", "John Doe")

4. Fill email field
browser.fill("input[type='email']", "john@example.com")

5. Fill message textarea
browser.fill("textarea[name='message']", "I would like to inquire about your services.")

6. Submit form
browser.click("button[type='submit']")

7. Wait for success message
browser.wait_for_element(".success-message")
```

### Example 2: Registration Form with Various Field Types

**Form Structure**:
- Username (text)
- Email (email)
- Password (password)
- Confirm Password (password)
- Country (dropdown)
- Terms agreement (checkbox)
- Submit button

**Steps**:
```
1. Navigate and wait
browser.navigate("https://example.com/register")
browser.wait_for_element("form#registration")

2. Fill text fields
browser.fill("input[name='username']", "johndoe123")
browser.fill("input[name='email']", "john@example.com")

3. Fill password fields
browser.fill("input[name='password']", "SecurePass123!")
browser.fill("input[name='password_confirm']", "SecurePass123!")

4. Select from dropdown
browser.select_by_text("select[name='country']", "United States")

5. Check terms checkbox
browser.check("input#accept-terms")

6. Submit
browser.click("button[type='submit']")

7. Verify registration success
browser.wait_for_element(".welcome-message")
```

### Example 3: Multi-Step Form

**Form Structure**:
- Step 1: Personal info (name, email)
- Step 2: Address (street, city, zip)
- Step 3: Payment (card number, expiry)

**Steps**:
```
Step 1: Personal Info
browser.navigate("https://example.com/checkout")
browser.fill("input[name='first_name']", "John")
browser.fill("input[name='last_name']", "Doe")
browser.fill("input[name='email']", "john@example.com")
browser.click("button.next-step")

Step 2: Address
browser.wait_for_element("#address-form")
browser.fill("input[name='street']", "123 Main St")
browser.fill("input[name='city']", "Springfield")
browser.fill("input[name='zip']", "12345")
browser.click("button.next-step")

Step 3: Payment
browser.wait_for_element("#payment-form")
browser.fill("input[name='card_number']", "4111111111111111")
browser.fill("input[name='expiry']", "12/25")
browser.fill("input[name='cvv']", "123")
browser.click("button[type='submit']")

Confirmation
browser.wait_for_element(".order-confirmation")
```

### Example 4: Search Form

**Task**: Fill and submit a search form

**Steps**:
```
1. Locate search box
browser.fill("input[name='q']", "laptop computers")

or

browser.fill("input#search-box", "laptop computers")

2. Submit search
Option A: Click search button
browser.click("button[type='submit']")

Option B: Press Enter key
browser.type("input[name='q']", "{ENTER}")

3. Wait for results
browser.wait_for_element(".search-results")
```

## Best Practices

### Field Identification
- **Use stable selectors**: Prefer `name` or `id` attributes over generated classes
- **Test selectors**: Verify selectors return the correct field
- **Handle dynamic forms**: Some forms load fields conditionally; wait for them

### Data Validation
- **Validate before filling**: Check data format matches field requirements
- **Handle required fields**: Ensure all required fields are filled
- **Check field constraints**: Respect max length, allowed characters, etc.

### Security
- **Never hardcode credentials**: Use secure credential management
- **Handle sensitive data carefully**: Clear password fields after use if testing
- **Use HTTPS**: Ensure forms are submitted over secure connections
- **Respect CSRF tokens**: Some forms require tokens; ensure they're present

### Error Handling
- **Check for errors**: Look for validation error messages
- **Retry on failure**: Network issues may cause failures; retry with backoff
- **Verify submission**: Don't assume success; check for confirmation
- **Handle timeouts**: Long-running forms may timeout; increase limits

### Performance
- **Batch operations**: Fill multiple fields before waiting
- **Avoid unnecessary delays**: Only wait when needed
- **Reuse sessions**: Don't re-login if already authenticated

## Common Issues

### Issue 1: Field Not Found
**Problem**: Selector doesn't match any element

**Solutions**:
- Verify selector in DevTools: `document.querySelector('selector')`
- Check if form has loaded completely
- Wait for the field to appear: `browser.wait_for_element(selector)`
- Check if field is in an iframe (need to switch context)

### Issue 2: Field Not Interactable
**Problem**: Field exists but can't be filled

**Solutions**:
- Wait for field to be visible and enabled
- Check if field is covered by another element
- Scroll field into view before filling
- Dismiss popups or modals that may be blocking

### Issue 3: Value Not Set
**Problem**: Field is filled but value doesn't persist

**Solutions**:
- Use `type` instead of `fill` to trigger events
- Check if JavaScript is clearing the field
- Verify field accepts the value type
- Fill field with correct format (dates, numbers, etc.)

### Issue 4: Form Doesn't Submit
**Problem**: Submit button doesn't work

**Solutions**:
- Ensure all required fields are filled
- Check for validation errors
- Verify submit button selector is correct
- Try pressing Enter in a field instead of clicking button
- Check if form uses AJAX submission (need to wait differently)

### Issue 5: CAPTCHA or Bot Detection
**Problem**: Form has bot protection

**Solutions**:
- This skill cannot solve CAPTCHAs automatically
- Consider manual intervention or CAPTCHA solving services
- Check if site offers testing environment without CAPTCHA
- Use proper user agent and realistic timing

## Troubleshooting Guide

### Step 1: Verify Form Loaded
```
Check: Can you see the form in the browser?
Action: Use browser.wait_for_element("form selector")
```

### Step 2: Check Field Selectors
```
Test: document.querySelector('your-selector') in DevTools
Verify: Returns the correct input element
```

### Step 3: Verify Field is Fillable
```
Check: Is the field visible, enabled, not readonly?
Action: Inspect field attributes
Fix: Wait for field to be ready, scroll into view
```

### Step 4: Check Data Format
```
Verify: Data matches field requirements
- Email fields need valid email format
- Number fields need numeric values
- Date fields need proper format (YYYY-MM-DD)
```

### Step 5: Handle Validation
```
After filling: Check for validation error messages
Look for: .error, .invalid, aria-invalid="true"
Fix: Correct the data format or value
```

## Advanced Techniques

### Technique 1: Handle Dynamic Required Fields
Some forms show/hide required fields based on other selections:
1. Fill trigger field first (e.g., select country)
2. Wait for dependent fields to appear
3. Fill the new fields
4. Continue with form

### Technique 2: Handle Form Validation
Modern forms validate as you type:
1. Fill field
2. Wait briefly for validation to run
3. Check for validation errors
4. Correct if needed before moving to next field

### Technique 3: Handle Autofill
Browsers may autofill forms:
1. Check if fields already have values
2. Clear fields if you need specific values
3. Or skip filling if autofill is acceptable

### Technique 4: Handle Dynamic Dropdowns
Some dropdowns load options dynamically:
1. Click/focus the dropdown first
2. Wait for options to load
3. Then select the option

## Related Skills

- **browser-automation.md**: For fundamental browser control (navigation, clicking, waiting)
- **data-validation.md**: For validating data before filling forms and after submission
- **web-scraping.md**: Often used together to extract data and then fill forms with it

## Tool Reference

The following Browser4 tools are commonly used with this skill:

- `browser.navigate(url)` - Navigate to form page
- `browser.fill(selector, value)` - Fill text fields
- `browser.type(selector, value)` - Type into fields (triggers events)
- `browser.click(selector)` - Click buttons, checkboxes, radio buttons
- `browser.check(selector)` - Check checkboxes
- `browser.uncheck(selector)` - Uncheck checkboxes
- `browser.select(selector, value)` - Select dropdown options
- `browser.upload_file(selector, path)` - Upload files
- `browser.wait_for_element(selector)` - Wait for fields to appear
- `browser.get_value(selector)` - Verify field values
- `browser.clear(selector)` - Clear field contents

## Summary

Form filling with Browser4 involves:
1. **Navigate** to the form page
2. **Identify** all form fields and their types
3. **Prepare** data matching field requirements
4. **Fill** each field using appropriate methods
5. **Submit** the form and verify success

Always validate data before filling, handle errors gracefully, and respect security and privacy best practices.
