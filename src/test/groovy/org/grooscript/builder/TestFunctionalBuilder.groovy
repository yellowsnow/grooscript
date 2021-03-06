package org.grooscript.builder

import org.grooscript.FunctionalTest
import org.grooscript.GrooScript
import org.grooscript.JsGenerator

/**
 * Created by jorge on 02/05/14.
 */
class TestFunctionalBuilder extends FunctionalTest {

    String htmlResponse() {
        def result = '<html><head><title>Title</title></head><body>'
        result += script(jsFileText('grooscript.min.js'))
        result += script(jsFileText('jquery.min.js'))
        result += script(jsFileText('grooscript-tools.js'))
        result += script(GrooScript.convert(startFunction))
        result += script('$(document).ready(function() { $("body").append(getGrooviers()); });')
        result += '</body></html>'
        result
    }

    void testBuilderWorksWithGrooscriptTools() {
        assertScript '''
    @org.grooscript.asts.PhantomJsTest(url = 'http://localhost:8000/test', waitSeconds = 1)
    void doTest() {
        assert $('li').size() == 4, "Incorrect number of li's loaded is: ${$('li').size()}"
    }
    doTest()
'''
    }

    void setUp() {
        JsGenerator.generateGrooscriptToolsJs()
        super.setUp()
    }

    private getStartFunction() {
        '''
def getGrooviers = { ->
    HtmlBuilder.build {
        ul {
            ['Groovy', 'Grails', 'Gradle', 'Griffon'].each {
                li it
            }
        }
    }
}'''
    }
}
