# Baidu-specific interaction challenges are not documented

## Summary

Using browser4-cli on Baidu.com presents specific challenges (dynamic placeholder text, complex redirect URLs, JavaScript-heavy rendering) that are not addressed in the documentation. Since Chinese websites like Baidu, Taobao, and JD.com are likely targets for users, these platform-specific quirks should be documented.

## Steps to Reproduce

Attempt to use browser4-cli on Baidu.com.

## Actual Behavior

Baidu uses dynamic placeholder text in the search box (trending topics), complex redirect URLs, and JavaScript-heavy page rendering. The search button ref becomes stale after typing text into the search box. These are common challenges that any user will encounter, but they are not addressed in the documentation.

## Suggested Improvement

Add platform-specific notes for popular Chinese sites (Baidu, Taobao, JD.com) that are likely targets for browser4-cli users. Document known quirks, such as:
- The search button becoming stale after typing
- Handling dynamic placeholder text
- Recommended interaction workflows for these sites
- Expected timeout behavior on JavaScript-heavy pages

Labels: enhancement, documentation, low
