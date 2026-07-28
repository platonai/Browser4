module.exports = {
    testEnvironment: 'jsdom',
    roots: ['<rootDir>'],
    testMatch: ['**/*.test.js'],
    setupFiles: ['./jest.setup.js'],
    // The source files use global variables — define them here
    // so the test environment knows what to expect.
    transform: {},
};
