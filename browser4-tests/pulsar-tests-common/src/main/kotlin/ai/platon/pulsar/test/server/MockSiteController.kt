package ai.platon.pulsar.test.server

import ai.platon.browser4.common.B4ResourceLoader
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class MockSiteController {
    @GetMapping("/")
    fun home(): String {
        return "Welcome! This site is used for internal test."
    }

    @GetMapping("hello")
    fun hello(): String {
        return "Hello, World!"
    }

    @GetMapping("text", produces = ["text/plain"])
    fun text(): String {
        return "Hello, World! This is a plain text."
    }

    @GetMapping("csv", produces = ["text/csv"])
    fun csv(): String {
        return """
1,2,3,4,5,6,7
a,b,c,d,e,f,g
1,2,3,4,5,6,7
a,b,c,d,e,f,g
""".trimIndent()
    }

    @GetMapping("json", produces = ["application/json"])
    fun json(): String {
        return """{"message": "Hello, World! This is a json."}"""
    }

    @GetMapping("robots.txt", produces = ["application/text"])
    fun robots(): String {
        return """
            User-agent: *
            Disallow: /exec/obidos/account-access-login
            Disallow: /exec/obidos/change-style
            Disallow: /exec/obidos/flex-sign-in
            Disallow: /exec/obidos/handle-buy-box
            Disallow: /exec/obidos/tg/cm/member/
            Disallow: /gp/aw/help/id=sss
            Disallow: /gp/cart
            Disallow: /gp/flex
            Disallow: /gp/product/e-mail-friend
            Disallow: /gp/product/product-availability
            Disallow: /gp/product/rate-this-item
            Disallow: /gp/sign-in
            Disallow: /gp/reader
            Disallow: /gp/sitbv3/reader
        """.trimIndent()
    }

    @GetMapping("amazon/home.htm", produces = ["text/html"])
    fun amazonHome(): String {
        return B4ResourceLoader.readString("pages/amazon/home.htm")
    }

    @GetMapping("amazon/product.htm", produces = ["text/html"])
    fun amazonProduct(): String {
        return B4ResourceLoader.readString("pages/amazon/B08PP5MSVB.original.htm")
    }

    @GetMapping("assets/test-pages/form-page.html", produces = ["text/html"])
    fun formPage(): String {
        return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Form Test Page</title>
</head>
<body>
    <h1>Form Test Page</h1>
    <form id="testForm" action="/submit" method="post">
        <div>
            <label for="username">Username:</label>
            <input type="text" id="username" name="username" data-testid="username-input">
        </div>
        <div>
            <label for="email">Email:</label>
            <input type="email" id="email" name="email" data-testid="email-input">
        </div>
        <div>
            <label for="password">Password:</label>
            <input type="password" id="password" name="password" data-testid="password-input">
        </div>
        <div>
            <input type="checkbox" id="remember" name="remember" data-testid="remember-checkbox">
            <label for="remember">Remember me</label>
        </div>
        <div>
            <input type="checkbox" id="newsletter" name="newsletter" data-testid="newsletter-checkbox">
            <label for="newsletter">Subscribe to newsletter</label>
        </div>
        <div>
            <input type="radio" id="option1" name="option" value="1" data-testid="radio-option1">
            <label for="option1">Option 1</label>
            <input type="radio" id="option2" name="option" value="2" data-testid="radio-option2">
            <label for="option2">Option 2</label>
        </div>
        <div>
            <button type="button" id="clickButton" data-testid="click-button">Click Me</button>
            <button type="submit" id="submitButton" data-testid="submit-button">Submit</button>
        </div>
    </form>
    <div id="result" data-testid="result"></div>
    <div id="attrTest" data-custom="custom-value" title="Test Title" class="test-class" data-testid="attr-test-div">Attributes Test</div>
    <a href="https://example.com" id="testLink" target="_blank" rel="noopener" data-testid="test-link">Test Link</a>
    <script>
        document.getElementById('clickButton').addEventListener('click', function() {
            document.getElementById('result').textContent = 'Button clicked!';
        });
        document.getElementById('testForm').addEventListener('submit', function(e) {
            e.preventDefault();
            document.getElementById('result').textContent = 'Form submitted!';
        });
    </script>
</body>
</html>"""
    }

    @GetMapping("assets/test-pages/error-page.html", produces = ["text/html"])
    fun errorPage(): String {
        return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Error Test Page</title>
</head>
<body>
    <h1>Error Test Page</h1>
    <div id="emptyDiv" data-testid="empty-div"></div>
    <div id="contentDiv" data-testid="content-div"><p>This has content</p></div>
    <div id="hiddenDiv" style="display: none;" data-testid="hidden-div">Hidden content</div>
    <div id="delayedDiv" data-testid="delayed-div"></div>
    <script>
        setTimeout(function() {
            document.getElementById('delayedDiv').textContent = 'Delayed content loaded';
        }, 2000);
    </script>
</body>
</html>"""
    }

    @GetMapping("assets/test-pages/keyboard-test.html", produces = ["text/html"])
    fun keyboardPage(): String {
        return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Keyboard Test Page</title>
</head>
<body>
    <h1>Keyboard Test Page</h1>
    <div>
        <label for="keyInput">Type here:</label>
        <input type="text" id="keyInput" data-testid="key-input" placeholder="Type something...">
    </div>
    <div>
        <label for="focusInput">Focus test:</label>
        <input type="text" id="focusInput" data-testid="focus-input" placeholder="Focus test">
    </div>
    <div id="keyResult" data-testid="key-result"></div>
    <div id="focusResult" data-testid="focus-result"></div>
    <script>
        document.getElementById('keyInput').addEventListener('keypress', function(e) {
            document.getElementById('keyResult').textContent = 'Key pressed: ' + e.key;
        });
        document.getElementById('focusInput').addEventListener('focus', function() {
            document.getElementById('focusResult').textContent = 'Input focused';
        });
        document.getElementById('focusInput').addEventListener('blur', function() {
            document.getElementById('focusResult').textContent = 'Input blurred';
        });
    </script>
</body>
</html>"""
    }
}
