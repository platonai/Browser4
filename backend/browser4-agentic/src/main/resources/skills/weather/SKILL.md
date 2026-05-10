---
name: weather
description: Fetches current weather conditions and a 7-day forecast for a requested location using Open-Meteo geocoding and forecast APIs. Use when the user asks about weather, temperature, rain, wind, or a weekly forecast for a place.
allowed-tools: python
metadata:
  displayName: Weather
  version: "1.0.0"
  author: Browser4
  tags: "weather, forecast, climate, location"
---

# Weather Skill

## Description

Use this skill when a user needs current weather conditions or a 7-day forecast for a named location.
The bundled script resolves the location with Open-Meteo geocoding, fetches live forecast data without an API key,
and returns normalized JSON that is easy to summarize or quote directly.

## Dependencies

None

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| location | String | Yes | - | Free-form location such as `Seattle, WA`, `London`, or `Tokyo` |
| units | String | No | metric | Unit system: `metric` for Celsius and km/h, or `imperial` for Fahrenheit and mph |
| countryCode | String | No | - | Optional ISO 3166-1 alpha-2 country code to disambiguate places such as `US`, `GB`, or `JP` |

## Return Value

The script prints JSON with the following shape:

```json
{
  "query": {
    "location": "Seattle, WA",
    "units": "imperial",
    "countryCode": "US"
  },
  "resolvedLocation": {
    "name": "Seattle",
    "admin1": "Washington",
    "country": "United States",
    "countryCode": "US",
    "latitude": 47.61,
    "longitude": -122.33,
    "timezone": "America/Los_Angeles"
  },
  "current": {
    "time": "2026-04-09T09:00",
    "summary": "Partly cloudy",
    "temperature": 55.4,
    "temperatureUnit": "degF",
    "feelsLike": 53.8,
    "humidity": 68,
    "humidityUnit": "%",
    "precipitation": 0.0,
    "precipitationUnit": "inch",
    "windSpeed": 7.1,
    "windSpeedUnit": "mph",
    "windDirection": 220,
    "windDirectionUnit": "deg"
  },
  "forecast": [
    {
      "date": "2026-04-09",
      "summary": "Partly cloudy",
      "temperatureMax": 58.1,
      "temperatureMin": 45.3,
      "temperatureUnit": "degF",
      "precipitationProbabilityMax": 24,
      "precipitationProbabilityUnit": "%",
      "precipitationSum": 0.02,
      "precipitationSumUnit": "inch",
      "windSpeedMax": 12.4,
      "windSpeedUnit": "mph",
      "sunrise": "2026-04-09T06:31",
      "sunset": "2026-04-09T19:48"
    }
  ],
  "source": {
    "provider": "Open-Meteo",
    "forecastDays": 7
  }
}
```

## Usage Examples

1. Activate the skill documentation with `skill.activate("weather")` if it is not already active.
2. Fetch the live weather JSON with `skill.runScript("weather", "scripts/get_weather.py", [...args])`.
3. Summarize the returned JSON for the user, explicitly calling out the current conditions and the 7-day forecast.

### Current weather and 7-day forecast in metric units

```kotlin
skill.runScript(
    "weather",
    "scripts/get_weather.py",
    listOf("Berlin", "--units", "metric")
)
```

### Disambiguate a city with a country code

```kotlin
skill.runScript(
    "weather",
    "scripts/get_weather.py",
    listOf("Springfield", "--country-code", "US", "--units", "imperial")
)
```

## Error Handling

The script returns a non-zero exit code and an error JSON payload when:

- `location` is missing or blank
- `units` is not `metric` or `imperial`
- geocoding cannot resolve the requested location
- the upstream weather service is unavailable or returns malformed data

If multiple matching locations exist, use `countryCode` to disambiguate and rerun the script.

## Implementation Notes

- Uses only the Python standard library; no extra packages are required
- Fetches live weather from Open-Meteo geocoding and forecast endpoints
- Always returns the current conditions plus exactly 7 forecast days
- Maps WMO weather codes to readable summaries before returning JSON

## See Also

- [Web Scraping Skill](../web-scraping/SKILL.md)
- [Data Validation Skill](../data-validation/SKILL.md)
