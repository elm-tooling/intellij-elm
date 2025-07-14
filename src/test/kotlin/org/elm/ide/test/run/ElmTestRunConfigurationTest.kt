package org.elm.ide.test.run

import org.elm.ide.test.run.ElmTestRunConfiguration.Companion.readOptions
import org.elm.ide.test.run.ElmTestRunConfiguration.Companion.writeOptions
import org.elm.ide.test.run.ElmTestRunConfiguration.Options
import org.jdom.Element
import org.junit.Assert.assertEquals
import org.junit.Test

class ElmTestRunConfigurationTest {

    @Test
    fun writeOptions() {
        val root = Element("ROOT")

        val options = Options()
        options.elmFolder = "folder"
        options.filteredTestConfig = ElmTestRunConfiguration.FilteredTest.from("Foo.elm", "Foo")

        writeOptions(options, root)

        assertEquals(1, root.children.size.toLong())
        assertEquals(ElmTestRunConfiguration::class.java.simpleName, root.children[0].name)
        assertEquals(3, root.children[0].attributes.size.toLong())
        assertEquals("elm-folder", root.children[0].attributes[0].name)
        assertEquals("folder", root.children[0].attributes[0].value)
        assertEquals("test-file-path", root.children[0].attributes[1].name)
        assertEquals("Foo.elm", root.children[0].attributes[1].value)
        assertEquals(false, options.filteredTestConfig?.testIsDirectory)
        assertEquals("Foo.elm", options.filteredTestConfig?.runnableFilePath())
    }

    @Test
    fun writeOptionsWithDirectory() {
        val root = Element("ROOT")

        val options = Options()
        options.elmFolder = "folder"
        options.filteredTestConfig = ElmTestRunConfiguration.FilteredTest.from("foo", "Foo")

        writeOptions(options, root)

        assertEquals(1, root.children.size.toLong())
        assertEquals(ElmTestRunConfiguration::class.java.simpleName, root.children[0].name)
        assertEquals(3, root.children[0].attributes.size.toLong())
        assertEquals("elm-folder", root.children[0].attributes[0].name)
        assertEquals("folder", root.children[0].attributes[0].value)
        assertEquals("test-file-path", root.children[0].attributes[1].name)
        assertEquals("foo", root.children[0].attributes[1].value)
        assertEquals(true, options.filteredTestConfig?.testIsDirectory)
        assertEquals("foo/**/*.elm", options.filteredTestConfig?.runnableFilePath())
        assertEquals(null, options.filteredTestConfig?.filter)
    }

    @Test
    fun writeOptionsWithFilter() {
        val root = Element("ROOT")

        val options = Options()
        options.elmFolder = "folder"
        options.filteredTestConfig = ElmTestRunConfiguration.FilteredTest.from("Foo.elm", "Foo", filter = "bar")

        writeOptions(options, root)

        assertEquals(1, root.children.size.toLong())
        assertEquals(ElmTestRunConfiguration::class.java.simpleName, root.children[0].name)
        assertEquals(4, root.children[0].attributes.size.toLong())
        assertEquals("elm-folder", root.children[0].attributes[0].name)
        assertEquals("folder", root.children[0].attributes[0].value)
        assertEquals("test-file-path", root.children[0].attributes[1].name)
        assertEquals("Foo.elm", root.children[0].attributes[1].value)
        assertEquals(false, options.filteredTestConfig?.testIsDirectory)
        assertEquals("Foo.elm", options.filteredTestConfig?.runnableFilePath())
        assertEquals("bar", options.filteredTestConfig?.filter)
    }

    @Test
    fun writeOptionsWithNullTestFile() {
        val root = Element("ROOT")

        val options = Options()
        options.elmFolder = "folder"

        writeOptions(options, root)

        assertEquals(1, root.children.size.toLong())
        assertEquals(ElmTestRunConfiguration::class.java.simpleName, root.children[0].name)
        assertEquals(1, root.children[0].attributes.size.toLong())
        assertEquals("elm-folder", root.children[0].attributes[0].name)
        assertEquals("folder", root.children[0].attributes[0].value)
    }

    @Test
    fun roundTrip() {
        val root = Element("ROOT")

        val options = Options()
        options.elmFolder = "folder"
        options.filteredTestConfig = ElmTestRunConfiguration.FilteredTest.from("Foo.elm", "foo")

        writeOptions(options, root)
        val options2 = readOptions(root)

        assertEquals(options.elmFolder, options2.elmFolder)
        assertEquals(options.filteredTestConfig, options2.filteredTestConfig)
        assertEquals(options.filteredTestConfig?.runnableFilePath(), options2.filteredTestConfig?.runnableFilePath())
    }

    @Test
    fun roundTripWithDirectory() {
        val root = Element("ROOT")

        val options = Options()
        options.elmFolder = "folder"
        options.filteredTestConfig = ElmTestRunConfiguration.FilteredTest.from("foo", "foo")

        writeOptions(options, root)
        val options2 = readOptions(root)

        assertEquals(options.elmFolder, options2.elmFolder)
        assertEquals(options.filteredTestConfig, options2.filteredTestConfig)
        assertEquals(options.filteredTestConfig?.runnableFilePath(), options2.filteredTestConfig?.runnableFilePath())
    }

    @Test
    fun roundTripWithFilter() {
        val root = Element("ROOT")

        val options = Options()
        options.elmFolder = "folder"
        options.filteredTestConfig = ElmTestRunConfiguration.FilteredTest.from("Foo.elm", "foo", filter = "bar")

        writeOptions(options, root)
        val options2 = readOptions(root)

        assertEquals(options.elmFolder, options2.elmFolder)
        assertEquals(options.filteredTestConfig, options2.filteredTestConfig)
        assertEquals(options.filteredTestConfig?.runnableFilePath(), options2.filteredTestConfig?.runnableFilePath())
        assertEquals(options.filteredTestConfig?.filter, options2.filteredTestConfig?.filter)
    }

    @Test
    fun roundTripWithNullTestFile() {
        val root = Element("ROOT")

        val options = Options()
        options.elmFolder = "folder"

        writeOptions(options, root)
        val options2 = readOptions(root)

        assertEquals(options.elmFolder, options2.elmFolder)
        assertEquals(options.filteredTestConfig, options2.filteredTestConfig)
        assertEquals(options.filteredTestConfig?.runnableFilePath(), options2.filteredTestConfig?.runnableFilePath())
    }

}