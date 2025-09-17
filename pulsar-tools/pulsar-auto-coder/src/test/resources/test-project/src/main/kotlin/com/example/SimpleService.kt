package com.example

/**
 * A simple service class for testing AutoCoder functionality
 */
class SimpleService {

    /**
     * Calculate the sum of two numbers
     */
    fun add(a: Int, b: Int): Int {
        return a + b
    }

    /**
     * Calculate the difference of two numbers
     */
    fun subtract(a: Int, b: Int): Int {
        return a - b
    }

    /**
     * Check if a number is positive
     */
    fun isPositive(number: Int): Boolean {
        return number > 0
    }

    /**
     * Get the length of a string
     */
    fun getStringLength(text: String): Int {
        return text.length
    }

    /**
     * Convert string to uppercase
     */
    fun toUpperCase(text: String): String {
        return text.uppercase()
    }
}