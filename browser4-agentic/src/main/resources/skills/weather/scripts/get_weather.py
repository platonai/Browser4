#!/usr/bin/env python
"""Fetch current weather and a 7-day forecast for a named location."""

from __future__ import annotations

import argparse
import json
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


GEOCODING_BASE_URL = "https://geocoding-api.open-meteo.com/v1/search"
FORECAST_BASE_URL = "https://api.open-meteo.com/v1/forecast"
USER_AGENT = "Browser4 weather skill/1.0"
REQUEST_TIMEOUT_SECONDS = 20

WEATHER_CODE_DESCRIPTIONS = {
    0: "Clear sky",
    1: "Mainly clear",
    2: "Partly cloudy",
    3: "Overcast",
    45: "Fog",
    48: "Depositing rime fog",
    51: "Light drizzle",
    53: "Moderate drizzle",
    55: "Dense drizzle",
    56: "Light freezing drizzle",
    57: "Dense freezing drizzle",
    61: "Slight rain",
    63: "Moderate rain",
    65: "Heavy rain",
    66: "Light freezing rain",
    67: "Heavy freezing rain",
    71: "Slight snow fall",
    73: "Moderate snow fall",
    75: "Heavy snow fall",
    77: "Snow grains",
    80: "Slight rain showers",
    81: "Moderate rain showers",
    82: "Violent rain showers",
    85: "Slight snow showers",
    86: "Heavy snow showers",
    95: "Thunderstorm",
    96: "Thunderstorm with slight hail",
    99: "Thunderstorm with heavy hail",
}


def build_url(base_url: str, params: dict[str, Any]) -> str:
    query = urllib.parse.urlencode(params)
    return f"{base_url}?{query}"


def fetch_json(url: str) -> dict[str, Any]:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
        charset = response.headers.get_content_charset("utf-8")
        payload = response.read().decode(charset)
    return json.loads(payload)


def fail(message: str, *, details: dict[str, Any] | None = None, exit_code: int = 1) -> None:
    error_payload: dict[str, Any] = {"error": message}
    if details:
        error_payload.update(details)
    print(json.dumps(error_payload, indent=2))
    raise SystemExit(exit_code)


def resolve_location(location: str, country_code: str | None) -> dict[str, Any]:
    search_terms = [location]
    if "," in location:
        simplified = location.split(",", 1)[0].strip()
        if simplified and simplified not in search_terms:
            search_terms.append(simplified)

    results: list[dict[str, Any]] = []
    for search_term in search_terms:
        params: dict[str, Any] = {
            "name": search_term,
            "count": 10,
            "language": "en",
            "format": "json",
        }
        data = fetch_json(build_url(GEOCODING_BASE_URL, params))
        results = data.get("results") or []
        if results:
            break

    if not results:
        fail(
            "Location could not be resolved",
            details={"location": location, "countryCode": country_code},
            exit_code=2,
        )

    if country_code:
        wanted = country_code.upper()
        for candidate in results:
            if (candidate.get("country_code") or "").upper() == wanted:
                return candidate

    return results[0]


def weather_summary(code: Any) -> str:
    if isinstance(code, (int, float)):
        return WEATHER_CODE_DESCRIPTIONS.get(int(code), f"Unknown weather code {int(code)}")
    return "Unknown"


def build_forecast_url(latitude: float, longitude: float, units: str) -> str:
    temperature_unit = "fahrenheit" if units == "imperial" else "celsius"
    wind_speed_unit = "mph" if units == "imperial" else "kmh"
    precipitation_unit = "inch" if units == "imperial" else "mm"

    params = {
        "latitude": latitude,
        "longitude": longitude,
        "timezone": "auto",
        "forecast_days": 7,
        "temperature_unit": temperature_unit,
        "wind_speed_unit": wind_speed_unit,
        "precipitation_unit": precipitation_unit,
        "current": ",".join(
            [
                "temperature_2m",
                "relative_humidity_2m",
                "apparent_temperature",
                "precipitation",
                "weather_code",
                "wind_speed_10m",
                "wind_direction_10m",
            ]
        ),
        "daily": ",".join(
            [
                "weather_code",
                "temperature_2m_max",
                "temperature_2m_min",
                "precipitation_probability_max",
                "precipitation_sum",
                "wind_speed_10m_max",
                "sunrise",
                "sunset",
            ]
        ),
    }
    return build_url(FORECAST_BASE_URL, params)


def to_daily_entries(data: dict[str, Any], units: dict[str, Any]) -> list[dict[str, Any]]:
    daily = data.get("daily") or {}
    dates = daily.get("time") or []
    entries: list[dict[str, Any]] = []
    for index, date in enumerate(dates):
        weather_code = (daily.get("weather_code") or [None])[index]
        entries.append(
            {
                "date": date,
                "summary": weather_summary(weather_code),
                "temperatureMax": (daily.get("temperature_2m_max") or [None])[index],
                "temperatureMin": (daily.get("temperature_2m_min") or [None])[index],
                "temperatureUnit": units.get("temperature_2m_max"),
                "precipitationProbabilityMax": (daily.get("precipitation_probability_max") or [None])[index],
                "precipitationProbabilityUnit": units.get("precipitation_probability_max"),
                "precipitationSum": (daily.get("precipitation_sum") or [None])[index],
                "precipitationSumUnit": units.get("precipitation_sum"),
                "windSpeedMax": (daily.get("wind_speed_10m_max") or [None])[index],
                "windSpeedUnit": units.get("wind_speed_10m_max"),
                "sunrise": (daily.get("sunrise") or [None])[index],
                "sunset": (daily.get("sunset") or [None])[index],
            }
        )
    return entries


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("location", help="Named location such as 'Seattle, WA' or 'Tokyo'")
    parser.add_argument(
        "--units",
        choices=("metric", "imperial"),
        default="metric",
        help="Use metric (C, km/h) or imperial (F, mph) units",
    )
    parser.add_argument(
        "--country-code",
        dest="country_code",
        default=None,
        help="Optional ISO 3166-1 alpha-2 country code to disambiguate locations",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    location = args.location.strip()
    if not location:
        fail("Location must not be blank", exit_code=2)

    resolved = resolve_location(location, args.country_code)
    latitude = resolved.get("latitude")
    longitude = resolved.get("longitude")
    if latitude is None or longitude is None:
        fail("Resolved location is missing coordinates", details={"location": location}, exit_code=2)

    forecast = fetch_json(build_forecast_url(latitude, longitude, args.units))
    current = forecast.get("current") or {}
    current_units = forecast.get("current_units") or {}
    daily_units = forecast.get("daily_units") or {}

    payload = {
        "query": {
            "location": location,
            "units": args.units,
            "countryCode": args.country_code.upper() if args.country_code else None,
        },
        "resolvedLocation": {
            "name": resolved.get("name"),
            "admin1": resolved.get("admin1"),
            "country": resolved.get("country"),
            "countryCode": resolved.get("country_code"),
            "latitude": latitude,
            "longitude": longitude,
            "timezone": resolved.get("timezone") or forecast.get("timezone"),
        },
        "current": {
            "time": current.get("time"),
            "summary": weather_summary(current.get("weather_code")),
            "temperature": current.get("temperature_2m"),
            "temperatureUnit": current_units.get("temperature_2m"),
            "feelsLike": current.get("apparent_temperature"),
            "feelsLikeUnit": current_units.get("apparent_temperature"),
            "humidity": current.get("relative_humidity_2m"),
            "humidityUnit": current_units.get("relative_humidity_2m"),
            "precipitation": current.get("precipitation"),
            "precipitationUnit": current_units.get("precipitation"),
            "windSpeed": current.get("wind_speed_10m"),
            "windSpeedUnit": current_units.get("wind_speed_10m"),
            "windDirection": current.get("wind_direction_10m"),
            "windDirectionUnit": current_units.get("wind_direction_10m"),
        },
        "forecast": to_daily_entries(forecast, daily_units),
        "source": {
            "provider": "Open-Meteo",
            "forecastDays": 7,
            "geocodingUrl": GEOCODING_BASE_URL,
            "forecastUrl": FORECAST_BASE_URL,
        },
    }

    print(json.dumps(payload, indent=2))


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as exc:
        fail(
            f"Weather service returned HTTP {exc.code}",
            details={"reason": exc.reason},
            exit_code=3,
        )
    except urllib.error.URLError as exc:
        fail(
            "Weather service request failed",
            details={"reason": str(exc.reason)},
            exit_code=3,
        )
    except json.JSONDecodeError as exc:
        fail(
            "Weather service returned invalid JSON",
            details={"reason": str(exc)},
            exit_code=3,
        )
