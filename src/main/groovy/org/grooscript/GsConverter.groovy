package org.grooscript

import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.ast.stmt.*
import org.codehaus.groovy.ast.expr.*
import org.grooscript.util.Util
import org.grooscript.util.GsConsole
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.CompilationUnit
import static org.codehaus.groovy.control.customizers.builder.CompilerCustomizationBuilder.withConfig

/**
 * JFL 27/08/12
 */
class GsConverter {

    //Indent for pretty print
    def indent
    def static final TAB = '  '
    def String resultScript
    def Stack<String> classNameStack = new Stack<String>()
    def Stack<String> superNameStack = new Stack<String>()
    //Use for variable scoping, for class variable names and function names mainly
    def Stack variableScoping = new Stack()
    def Stack variableStaticScoping = new Stack()
    def Stack returnScoping = new Stack()
    //Use por function variable names
    def Stack actualScope = new Stack()
    def String superMethodBegin = 'super_'
    def boolean processingClosure = false

    def inheritedVariables = [:]

    //Where code of native functions stored, as a map. Used for GsNative annotation
    def nativeFunctions

    //Adds a console info if activated
    def consoleInfo = false

    //Control switch inside switch
    def switchCount = 0
    def addClosureSwitchInitialization = false

    //We get this function names from unused_functions.groovy
    //Not now, changed, maybe in future can use a file for define that
    def assertFunction
    def printlnFunction

    //Conversion Options
    def addClassNames = false
    def convertDependencies = true
    Closure customization = null

    //Constant names for javascript out
    def static final GS_OBJECT = 'gSobject'

    /**
     * Constructor
     * @return
     */
    def GsConverter() {
        initFunctionNames()
    }

    def private initFunctionNames() {
        assertFunction = 'gSassert'
        printlnFunction = 'gSprintln'
    }

    def private addToActualScope(variableName) {
        if (!actualScope.isEmpty()) {
            actualScope.peek().add(variableName)
        }
    }

    def private actualScopeContains(variableName) {
        if (!actualScope.isEmpty()) {
            return actualScope.peek().contains(variableName)
        } else {
            return false
        }
    }

    /**
     * Converts Groovy script to Javascript
     * @param String script in groovy
     * @return String script in javascript
     */
    def toJs(String script) {
        return toJs(script,null)
    }

    /**
     * Converts Groovy script to Javascript
     * @param String script in groovy
     * @param String classPath to add to classpath
     * @return String script in javascript
     */
    def toJs(String script,Object classPath) {
        def result
        //Classpath must be a String or a list
        if (classPath && !(classPath instanceof String || classPath instanceof Collection)) {
            throw new Exception('The classpath must be a String or a List')
        }
        //Script not empty plz!
        def phase = 0
        if (script) {

            try {

                nativeFunctions = Util.getNativeFunctions(script)

                if (consoleInfo) {
                    GsConsole.message('Getting ast from code...')
                }
                //def AstBuilder asts
                def list = getAstFromText(script,classPath)

                if (consoleInfo) {
                    GsConsole.message('Processing AST...')
                }

                phase++
                result = processAstListToJs(list)

                if (consoleInfo) {
                    GsConsole.message('Code processed.')
                }
            } catch (e) {
                GsConsole.error('Error getting AST from script: '+e.message)
                if (phase==0) {
                    throw new Exception("Compiler ERROR on Script -"+e.message)
                } else {
                    throw new Exception("Compiler END ERROR on Script -"+e.message)
                }
            }
        }
        result
    }

    /**
     * Get AST tree from code, add classpath to compilation
     * @param text
     * @param classpath
     * @return
     */
    def getAstFromText(text,Object classpath) {

        if (consoleInfo) {
            GsConsole.message('Converting string code to AST')
            GsConsole.message(' Option convertDependencies: '+convertDependencies)
            GsConsole.message(' Classpath: '+classpath)
        }
        //By default, convertDependencies = true
        //All the imports in a file are added to the source to be compiled, if not added, compiler fails
        def classesToConvert = []
        if (!convertDependencies) {
            def matcher = text =~ /\bclass\s+(\w+)\s*\{/
            matcher.each {
                //println 'Matcher1->'+it[1]
                classesToConvert << it[1]
            }
        }

        def scriptClassName = "script" + System.currentTimeMillis()
        GroovyClassLoader classLoader = new GroovyClassLoader()
        //Add classpath to classloader
        if (classpath) {
            if (classpath instanceof Collection) {
                classpath.each {
                    classLoader.addClasspath(it)
                }
            } else {
                classLoader.addClasspath(classpath)
            }
        }
        GroovyCodeSource codeSource = new GroovyCodeSource(text, scriptClassName + ".groovy", "/groovy/script")
        CompilerConfiguration conf = new CompilerConfiguration()
        //Add classpath to configuration
        if (classpath && classpath instanceof String) {
            conf.setClasspath(classpath)
        }
        if (classpath && classpath instanceof Collection) {
            conf.setClasspathList(classpath)
        }
        if (customization) {
            withConfig(conf, customization)
        }
        CompilationUnit cu = compiledCode(conf, codeSource, classLoader, text)

        // collect all the ASTNodes into the result, possibly ignoring the script body if desired
        def list = cu.ast.modules.inject([]) {List acc, ModuleNode node ->
            //println ' Acc node->'+node+ ' - '+ node.getStatementBlock().getStatements().size()
            if (node.statementBlock) {
                acc.add(node.statementBlock)

                node.classes?.each { ClassNode cl ->

                    if (!(cl.name == scriptClassName) && cl.isPrimaryClassNode()) {

                        //If we dont want to convert dependencies in the result
                        if (!convertDependencies) {
                            //println 'List->'+classesToConvert
                            //println 'Name->'+cl.name
                            if (classesToConvert.contains(translateClassName(cl.name))) {
                                acc << cl
                            }
                        } else {
                            acc << cl
                        }
                    } else {
                        if (cl.name == scriptClassName && cl.methods) {
                            //Lets take a look to script methods, and add methods in script
                            cl.methods.each { MethodNode methodNode ->
                                if (!(methodNode.name in ['main','run'])) {
                                    //println '  add methodNode->'+methodNode
                                    acc << methodNode
                                }
                            }
                        }
                    }
                }
            }
            acc
        }
        if (consoleInfo) {
            GsConsole.message('Done converting string code to AST. Number of nodes: '+list.size())
        }
        return list
    }

    private def compiledCode(conf, codeSource, classLoader, text) {
        try {
            def compilationUnit = new CompilationUnit(conf, codeSource.codeSource, classLoader)
            compilationUnit.addSource(codeSource.getName(), text)
            compilationUnit.compile(CompilePhase.INSTRUCTION_SELECTION.phaseNumber)
        } catch (e) {
            GsConsole.error 'Compilation error in INSTRUCTION_SELECTION phase'
            throw e
        }

        def compilationUnitFinal = new CompilationUnit(conf, codeSource.codeSource, classLoader)
        compilationUnitFinal.addSource(codeSource.getName(), text)
        compilationUnitFinal.compile(CompilePhase.SEMANTIC_ANALYSIS.phaseNumber)
        compilationUnitFinal
    }

    /**
     * Process an AST List from Groovy code to javascript script
     * @param list
     * @return
     */
    def processAstListToJs(list) {
        def result
        indent = 0
        resultScript = ''
        if (list && list.size()>0) {
            //println '-----------------Size('+list.size+')->'+list
            variableScoping.clear()
            variableScoping.push([])
            variableStaticScoping.clear()
            variableStaticScoping.push([])
            actualScope.clear()
            actualScope.push([])
            //Store all methods here
            def methodList = []
            //Store all classes here
            def classList = []
            //We process blocks at the end
            def listBlocks = []
            list.each { it ->
                //println '------------------------------------it->'+it
                if (it instanceof BlockStatement) {
                    listBlocks << it
                } else if (it instanceof ClassNode) {
                    if (!it.isInterface()) {
                        classList << it
                    }
                } else if (it instanceof MethodNode) {
                    methodList << it
                } else {
                    GsConsole.error("AST Node not supported (${it?.class?.simpleName}).")
                }
            }

            //Process list of classes
            if (classList) {
                if (consoleInfo) {
                    GsConsole.message('Processing class list...')
                }
                processClassList(classList)
                if (consoleInfo) {
                    GsConsole.message('Done class list.')
                }
            }

            //Process list of methods
            methodList?.each { MethodNode methodNode ->
                if (consoleInfo) {
                    GsConsole.message('Processing method '+methodNode.name)
                }
                //processMethodNode(methodNode)
                processBasicFunction("var ${methodNode.name}",methodNode,false)
            }

            //Process blocks after
            listBlocks?.each { it->
                processBlockStament(it,false)
            }

            result = resultScript
        }
        result
    }

    //Process list of classes in correct order, inheritance order
    //Save list of variables for inheritance
    def private processClassList(List<ClassNode> list) {

        def finalList = []
        def extraClasses = []
        def enumClasses = []
        while ((finalList.size()+extraClasses.size()+enumClasses.size())<list.size()) {

            list.each { ClassNode it ->
                //println 'it->'+it.name+' super - '+it.superClass.name
                if (it.superClass.name=='java.lang.Object')  {
                    if (!finalList.contains(it.name)) {
                        //println 'Adding '+it.name+' - '+it.isInterface()
                        finalList.add(it.name)
                    }
                } else {
                    //Expando allowed
                    if (it.superClass.name=='groovy.lang.Script') {
                        extraClasses.add(it.name)
                    } else {
                        //If father in the list, we can add it
                        if (finalList.contains(it.superClass.name)) {
                            //println 'Adding 2 '+it.name+' - '+it.isInterface()
                            finalList.add(it.name)
                        } else {
                            //Looking for superclass, only accepts superclass a class in same script
                            if (it.superClass.name.startsWith('java.') ||
                                it.superClass.name.startsWith('groovy.')) {
                                if (it.superClass.name=='java.lang.Enum') {
                                    enumClasses.add(it.name)
                                } else {
                                    throw new Exception('Inheritance not Allowed on '+it.name)
                                }
                            }
                        }
                    }
                }

            }
        }
        //Finally process classes in order
        finalList.each { String nameClass ->
            if (consoleInfo) {
                GsConsole.message('  Processing class '+nameClass)
            }
            processClassNode(list.find { ClassNode it ->
                return it.name == nameClass
            })
            if (consoleInfo) {
                GsConsole.message('  Processing class done.')
            }
        }
        //Expandos - Nothing to do!
        extraClasses.each { String nameClass ->
            //println 'Class->'+nameClass
            processScriptClassNode(list.find { ClassNode it ->
                return it.name == nameClass
            })
        }
        //Enums!
        enumClasses.each { String nameClass ->
            processEnum(list.find { ClassNode it ->
                return it.name == nameClass
            })
        }
    }

    /**
     * Create code the js class definition, for execute constructor
     * @param numberArguments
     * @param paramList
     * @return
     */
    def private addConditionConstructorExecution(numberArguments,paramList) {

        addScript("if (arguments.length==${numberArguments}) {")
        addScript("${GS_OBJECT}.${translateClassName(classNameStack.peek())}${numberArguments}")

        addScript '('
        def count = 0
        paramList?.each { param ->
            if (count>0) addScript ', '
            addScript("arguments[${count}]")
            count++
        }
        addScript ')'

        addScript('; }')
        addLine()
    }

    def private translateClassName(String name) {
        def result = name
        def i
        while ((i = result.indexOf('.'))>=0) {
            result = result.substring(i+1)
        }

        result
    }

    def private processScriptClassNode(ClassNode node) {

        //Push name in stack
        variableScoping.push([])
        actualScope.push([])

        addLine()

        //Adding initial values of properties
        /*
        node?.properties?.each { it->
            println 'Property->'+it; println 'initialExpresion->'+it.initialExpression
            if (it.initialExpression) {
                addScript("${GS_OBJECT}.${it.name} = ")
                visitNode(it.initialExpression)
                addScript(';')
                addLine()
            } else {
                addScript("${GS_OBJECT}.${it.name} = null;")
                addLine()
            }

            //We add variable names of the class
            variableScoping.peek().add(it.name)
        }*/

        //Methods
        node?.methods?.each {
            if (it.name!='main' && it.name!='run') {
                //Add too method names to variable scoping
                variableScoping.peek().add(it.name)
                processBasicFunction(it.name,it,false)
            }
        }

        addLine()

        //Remove variable class names from the list
        variableScoping.pop()
        actualScope.pop()

    }

    def private addPropertyToClass(fieldOrProperty,isStatic) {

        def previous = GS_OBJECT
        if (isStatic) {
            previous = ''
        }

        if (fieldOrProperty.initialExpression) {
            addScript("${previous}.${fieldOrProperty.name} = ")
            visitNode(fieldOrProperty.initialExpression)
            addScript(';')
            addLine()
        } else {
            addScript("${previous}.${fieldOrProperty.name} = null;")
            addLine()
        }
    }

    def private addPropertyStaticToClass(String name) {

        addScript("${GS_OBJECT}.__defineGetter__('${name}', function(){ return ${translateClassName(classNameStack.peek())}.${name}; });")
        addLine()
        addScript("${GS_OBJECT}.__defineSetter__('${name}', function(gSval){ ${translateClassName(classNameStack.peek())}.${name} = gSval; });")
        addLine()
    }

    def private haveAnnotationNonConvert(annotations) {
        boolean exit = false
        annotations.each { AnnotationNode it ->
            //If dont have to convert then exit
            if (it.getClassNode().nameWithoutPackage=='GsNotConvert') {
                exit = true
            }
        }
        return exit
    }

    def private haveAnnotationNative(annotations) {
        boolean exit = false
        annotations.each { AnnotationNode it ->
            //If native then exit
            if (it.getClassNode().nameWithoutPackage=='GsNative') {
                exit = true
            }
        }
        return exit
    }

    def private putGsNativeMethod(String name,MethodNode method) {

        //addScript("${GS_OBJECT}.${method.name} = function(")
        addScript("${name} = function(")
        actualScope.push([])
        processFunctionOrMethodParameters(method,false,false)
        actualScope.pop()
        addScript(nativeFunctions[method.name])
        addLine()
        indent--
        removeTabScript()
        addScript('}')
        addLine()
    }

    def private processClassNode(ClassNode node) { //Starting class conversion

        //Exit if dont have to convert
        if (haveAnnotationNonConvert(node.annotations)) {
            return 0
        }

        addLine()

        //Push name in stack
        classNameStack.push(node.name)
        variableScoping.push([])
        variableStaticScoping.push([])

        addScript("function ${translateClassName(node.name)}() {")

        indent ++
        addLine()
        superNameStack.push(node.superClass.name)
        //Allowed inheritance
        if (node.superClass.name != 'java.lang.Object') {
            //println 'Allowed!'+ node.superClass.class.name
            addScript("var ${GS_OBJECT} = ${translateClassName(node.superClass.name)}();")
            //We add to this class scope variables of fathers
            variableScoping.peek().addAll(inheritedVariables[node.superClass.name])
        } else {
            addScript("var ${GS_OBJECT} = inherit(gsBaseClass,'${translateClassName(node.name)}');")
        }
        addLine()
        //Add class name and super name
        if (addClassNames) {
            addClassNames(node.name, (node.superClass.name != 'java.lang.Object'?node.superClass.name:null))
        }

        if (consoleInfo) {
            GsConsole.message("   Processing class ${node.name}, step 1")
        }

        //Adding initial values of properties
        node?.properties?.each { it-> //println 'Property->'+it; println 'initialExpresion->'+it.initialExpression
            if (!it.isStatic()) {
                addPropertyToClass(it,false)
                //We add variable names of the class
                variableScoping.peek().add(it.name)
            } else {
                variableStaticScoping.peek().add(it.name);
                addPropertyStaticToClass(it.name)
            }
        }

        //Add fields not added as properties
        node.fields.each { FieldNode field ->
            if (field.owner.name == node.name && (field.isPublic()|| !node.properties.any { it.name == field.name})) {
                if (!field.isStatic()) {
                    addPropertyToClass(field,false)
                    variableScoping.peek().add(field.name)
                } else {
                    variableStaticScoping.peek().add(field.name)
                    addPropertyStaticToClass(field.name)
                }
            }
        }

        if (consoleInfo) {
            GsConsole.message("   Processing class ${node.name}, step 2")
        }

        //Save variables from this class for use in 'son' classes
        inheritedVariables.put(node.name,variableScoping.peek())
        //Ignoring fields
        //node?.fields?.each { println 'field->'+it  }

        //Methods
        node?.methods?.each { MethodNode it -> //println 'method->'+it;
            //Add too method names to variable scoping
            if (!it.isStatic() && !it.isAbstract()) {
                variableScoping.peek().add(it.name)
            }
        }
        node?.methods?.each { MethodNode it -> //println 'method->'+it;

            if (!haveAnnotationNonConvert(it.annotations) && !it.isAbstract()) {
                //Process the methods
                if (haveAnnotationNative(it.annotations) && !it.isStatic()) {
                    putGsNativeMethod("${GS_OBJECT}.${it.name}",it)
                } else if (!it.isStatic()) {
                    processMethodNode(it,false)
                } else {
                    //We put the number of params as x? name variables
                    def numberParams = 0
                    if (it.parameters && it.parameters.size()>0) {
                        numberParams = it.parameters.size()
                    }
                    def params = []
                    numberParams.times { number ->
                        params << 'x'+number
                    }

                    addScript("${GS_OBJECT}.${it.name} = function(${params.join(',')}) { return ${translateClassName(node.name)}.${it.name}(")
                    addScript(params.join(','))
                    addScript("); }")
                    addLine()
                }
            }
        }

        if (consoleInfo) {
            GsConsole.message("   Processing class ${node.name}, step 3")
        }

        //Constructors
        //If no constructor with 1 parameter, we create 1 that get a map, for put value on properties
        boolean has1parameterConstructor = false
        //boolean has0parameterConstructor = false
        node?.declaredConstructors?.each { MethodNode it->
            def numberArguments = it.parameters?.size()
            if (numberArguments==1) {
                has1parameterConstructor = true
            }
            //if (it.parameters?.size()==0) {
            //    has0parameterConstructor = true
            //}
            processMethodNode(it,true)

            addConditionConstructorExecution(numberArguments,it.parameters)

        }
        if (!has1parameterConstructor) {
            addScript("${GS_OBJECT}.${translateClassName(node.name)}1 = function(map) { gSpassMapToObject(map,this); return this;};")
            addLine()
            addScript("if (arguments.length==1) {${GS_OBJECT}.${translateClassName(node.name)}1(arguments[0]); }")
            addLine()
        }

        addLine()
        indent --
        addScript("return ${GS_OBJECT};")
        addLine()
        addScript('};')
        addLine()

        if (consoleInfo) {
            GsConsole.message("   Processing class ${node.name}, step 4")
        }

        //Static methods
        node?.methods?.each { MethodNode method ->
            if (!haveAnnotationNonConvert(method.annotations)) {
                if (method.isStatic()) {
                    if (haveAnnotationNative(method.annotations)) {
                        putGsNativeMethod("${translateClassName(node.name)}.${method.name}",method)
                    } else {
                        processBasicFunction("${translateClassName(node.name)}.${method.name}",method,false)
                    }
                }
            }
        }

        //Static properties
        node?.properties?.each { it-> //println 'Property->'+it; println 'initialExpresion->'+it.initialExpression
            if (it.isStatic()) {
                addScript(translateClassName(node.name))
                addPropertyToClass(it,true)
            }
        }

        //Remove variable class names from the list
        variableScoping.pop()
        variableStaticScoping.pop()

        //Pop name in stack
        classNameStack.pop()
        superNameStack.pop()

        //Finish class conversion
        if (consoleInfo) {
            GsConsole.message("   Processing class ${node.name}, Done.")
        }
    }

    def addClassNames(actualClassName,superClassName) {

        if (superClassName) {
            addScript("var temp = ${GS_OBJECT}.gSclass;")
            addLine()
            addScript("${GS_OBJECT}.gSclass = [];")
            addLine()
            addScript("${GS_OBJECT}.gSclass.superclass = temp;")
            addLine()
        } else {
            addScript("${GS_OBJECT}.gSclass = [];")
            addLine()
            addScript("${GS_OBJECT}.gSclass.superclass = [];")
            addLine()
            addScript("${GS_OBJECT}.gSclass.superclass.name= 'java.lang.Object';")
            addLine()
            addScript("${GS_OBJECT}.gSclass.superclass.simpleName= 'Object';")
            addLine()
        }
        addScript("${GS_OBJECT}.gSclass.name = '${actualClassName}';")
        addLine()
        addScript("${GS_OBJECT}.gSclass.simpleName = '${translateClassName(actualClassName)}';")
        addLine()

    }

    def private processFunctionOrMethodParameters(functionOrMethod, boolean isConstructor,boolean addItInParameter) {

        boolean first = true
        boolean lastParameterCanBeMore = false

        //Parameters with default values if not shown
        def initalValues = [:]

        //If no parameters, we add it by defaul
        if (addItInParameter && (!functionOrMethod.parameters || functionOrMethod.parameters.size()==0)) {
            addScript('it')
            addToActualScope('it')
        } else {

            functionOrMethod.parameters?.eachWithIndex { Parameter param, index ->

                //If the last parameter is an Object[] then, maybe, can get more parameters as optional
                if (param.type.name=='[Ljava.lang.Object;' && index+1==functionOrMethod.parameters.size()) {
                    lastParameterCanBeMore = true
                }
                //println 'pe->'+param.toString()+' - '+param.type.name //+' - '+param.type

                if (param.getInitialExpression()) {
                    //println 'Initial->'+param.getInitialExpression()
                    initalValues.putAt(param.name,param.getInitialExpression())
                }
                if (!first) {
                    addScript(', ')
                }
                addToActualScope(param.name)
                addScript(param.name)
                first = false
            }
        }
        addScript(') {')
        indent++
        addLine()

        //At start we add initialization of default values
        initalValues.each { key,value ->
            addScript("if (${key} === undefined) ${key} = ")
            visitNode(value)
            addScript(';')
            addLine()
        }

        //We add initialization of it inside switch closure function
        if (addClosureSwitchInitialization) {
            def name = 'gSswitch' + (switchCount - 1)
            addScript("if (it === undefined) it = ${name};")
            addLine()
            addClosureSwitchInitialization = false
        }

        if (lastParameterCanBeMore) {
            def Parameter lastParameter = functionOrMethod.parameters.last()
            addScript("if (arguments.length==${functionOrMethod.parameters.size()}) { ${lastParameter.name}=gSlist([arguments[${functionOrMethod.parameters.size()}-1]]); }")
            addLine()
            addScript("if (arguments.length<${functionOrMethod.parameters.size()}) { ${lastParameter.name}=gSlist([]); }")
            addLine()
            addScript("if (arguments.length>${functionOrMethod.parameters.size()}) {")
            addLine()
            addScript("  ${lastParameter.name}=gSlist([${lastParameter.name}]);")
            addLine()
            addScript("  for (gScount=${functionOrMethod.parameters.size()};gScount<arguments.length;gScount++) {")
            addLine()
            addScript("    ${lastParameter.name}.add(arguments[gScount]);")
            addLine()
            addScript("  }")
            addLine()
            addScript("}")
            addLine()
        }
    }

    def private putFunctionParametersAndBody(functionOrMethod, boolean isConstructor, boolean addItDefault) {

        actualScope.push([])

        processFunctionOrMethodParameters(functionOrMethod,isConstructor,addItDefault)

        //println 'Closure '+expression+' Code:'+expression.code
        if (functionOrMethod.code instanceof BlockStatement) {
            processBlockStament(functionOrMethod.code,!isConstructor)
        } else {
            GsConsole.error("FunctionOrMethod Code not supported (${functionOrMethod.code.class.simpleName})")
        }

        actualScope.pop()
    }

    def private processBasicFunction(name, method, isConstructor) {

        addScript("$name = function(")

        putFunctionParametersAndBody(method,isConstructor,true)

        indent--
        if (isConstructor) {
            addScript('return this;')
            addLine()
        } else {
            removeTabScript()
        }
        addScript('}')
        addLine()

    }

    def private processMethodNode(MethodNode method,isConstructor) {

        //Starting method conversion
        //Ignoring annotations
        //node?.annotations?.each {

        //Ignoring modifiers
        //visitModifiers(node.modifiers)

        //Ignoring init methods
        //if (node.name == '<init>') {
        //} else if (node.name == '<clinit>') {
        //visitType node.returnType

        def name =  method.name
        //Constructor method
        if (isConstructor) {
            //Add number of params to constructor name
            //BEWARE Atm only accepts constructor with different number or arguments
            name = translateClassName(classNameStack.peek()) + (method.parameters?method.parameters.size():'0')
        }

        processBasicFunction("${GS_OBJECT}['$name']",method,isConstructor)

    }

    /**
     * Process an AST Block
     * @param block
     * @param addReturn put 'return ' before last statement
     * @return
     */
    def private processBlockStament(block,addReturn) {
        if (block) {
            def number = 1
            //println 'Block->'+block
            if (block instanceof EmptyStatement) {
                println 'BlockEmpty->'+block.text
                //println 'Empty->'+block.getStatementLabel()
            } else {
                //println '------------------------------Block->'+block.text
                block.getStatements()?.each { statement ->
                    //println 'Block Statement-> size '+ block.getStatements().size() + ' number '+number+ ' it->'+it
                    //println 'is block-> '+ (it instanceof BlockStatement)
                    //println 'statement-> '+ statement.text
                    def position
                    returnScoping.push(false)
                    if (addReturn && ((number++)==block.getStatements().size()) && !(statement instanceof ReturnStatement)
                            && !(statement instanceof IfStatement) && !(statement instanceof WhileStatement)
                            && !(statement instanceof AssertStatement) && !(statement instanceof BreakStatement)
                            && !(statement instanceof CaseStatement) && !(statement instanceof CatchStatement)
                            && !(statement instanceof ContinueStatement) && !(statement instanceof DoWhileStatement)
                            && !(statement instanceof ForStatement) && !(statement instanceof SwitchStatement)
                            && !(statement instanceof ThrowStatement) && !(statement instanceof TryCatchStatement)
                            && !(statement.metaClass.expression && statement.expression instanceof DeclarationExpression)) {

                        //println 'Saving statemen->'+it
                        //println 'Saving return - '+ variableScoping.peek()
                        //this statement can be a complex statement with a return
                        //Go looking for a return statement in last statement
                        position = getSavePoint()
                        //We use actualScoping for getting return statement in this scope
                        //variableScoping.peek().remove(gSgotResultStatement)
                    }
                    processStatement(statement)
                    if (addReturn && position) {
                        if (!returnScoping.peek()) {
                            //No return statement, then we want add return
                            //println 'Yes!'+position
                            addScriptAt('return ',position)
                        }
                    }
                    returnScoping.pop()
                }
            }
        }
    }

    //???? there are both used
    def private processBlockStatement(block) {
        processBlockStament(block,false)
    }

    /**
     * Add a line to javascript output
     * @param script
     * @param line
     * @return
     */
    def private addLine() {
        //println "sc(${script}) line(${line})"
        if (resultScript) {
            resultScript += '\n'
        } else {
            resultScript = ''
        }
        indent.times { resultScript += TAB }
    }

    /**
     * Add a text to javascript output
     * @param text
     * @return
     */
    def private addScript(text) {
        //println 'adding ->'+text
        //indent.times { resultScript += TAB }
        resultScript += text
    }

    /**
     * Add text to javascript output at some position
     * @param text
     * @param position
     * @return
     */
    def private addScriptAt(text,position) {
        resultScript = resultScript.substring(0,position) + text + resultScript.substring(position)
    }

    /**
     * Remove a TAB from current javascript output
     * @return
     */
    def private removeTabScript() {
        resultScript = resultScript[0..resultScript.size()-1-TAB.size()]
    }

    /**
     * Get actual position in javascript output
     * @return
     */
    def private getSavePoint() {
        return resultScript.size()
    }

    /**
     * Process a statement, adding ; at the end
     * @param statement
     */
    def private void processStatement(Statement statement) {

        //println "statement (${statement.class.simpleName})->"+statement+' - '+statement.text
        visitNode(statement)

        //Adds ;
        if (resultScript) {
            resultScript += ';'
        }
        addLine()
        //println 'end statement'
    }

    def private processAssertStatement(AssertStatement statement) {
        Expression e = statement.booleanExpression
        addScript(assertFunction)
        addScript('(')
        visitNode(e)
        if (statement.getMessageExpression() && !(statement.messageExpression instanceof EmptyExpression)) {
            addScript(', ')
            visitNode(statement.messageExpression)
        }
        addScript(')')
    }

    def private handExpressionInBoolean(expression) {
        if (expression instanceof VariableExpression || expression instanceof PropertyExpression ||
                (expression instanceof NotExpression &&
                        expression.expression &&
                        (expression.expression instanceof VariableExpression || expression.expression instanceof PropertyExpression))) {
            if (expression instanceof NotExpression) {
                addScript('!gSbool(')
                visitNode(expression.expression)
            } else {
                addScript('gSbool(')
                visitNode(expression)
            }
            addScript(')')
        } else {
            visitNode(expression)
        }
    }

    def private processBooleanExpression(BooleanExpression expression) {
        //Groovy truth is a bit different, empty collections return false, we fix that here
        handExpressionInBoolean(expression.expression)
    }

    def private processExpressionStatement(ExpressionStatement statement) {
        Expression e = statement.expression
        visitNode(e)
    }

    def private processDeclarationExpression(DeclarationExpression expression) {
        //println 'l->'+expression.leftExpression
        //println 'r->'+expression.rightExpression
        //println 'v->'+expression.getVariableExpression()

        if (expression.isMultipleAssignmentDeclaration()) {
            TupleExpression tuple = (TupleExpression)(expression.getLeftExpression())
            def number = 0;
            tuple.expressions.each { Expression expr ->
                //println '->'+expr
                if (expr instanceof VariableExpression && expr.name!='_') {
                    addScript('var ')
                    processVariableExpression(expr)
                    addScript(' = ')
                    visitNode(expression.rightExpression)
                    addScript(".getAt(${number})")
                    if (number<tuple.expressions.size()) {
                        addScript(';')
                    }
                }
                number++
            }
        } else {

            //actualScope.add(expression.variableExpression.name)
            addToActualScope(expression.variableExpression.name)
            //variableScoping.add(expression.variableExpression.name)

            addScript('var ')
            processVariableExpression(expression.variableExpression)

            if (!(expression.rightExpression instanceof EmptyExpression)) {
                addScript(' = ')
                visitNode(expression.rightExpression)
            } else {
                addScript(' = null')
            }

        }
    }

    def private tourStack(Stack stack,variableName) {
        if (stack.isEmpty()) {
            return false
        } else if (stack.peek()?.contains(variableName)) {
            return true
        } else {
            //println 'going stack->'+stack.peek()
            def keep = stack.pop()
            def result = tourStack(stack,variableName)
            stack.push(keep)
            return result
        }
    }

    def private variableScopingContains(variableName) {
        //println 'vs('+variableName+')->'+fuckStack(variableScoping,variableName) //variableScoping.peek()?.contains(variableName) //variableScoping.search(variableName)
        //println 'actualScope->'+actualScope
        return tourStack(variableScoping,variableName)
    }

    def allActualScopeContains(variableName) {
        //println 'as('+variableName+')->'+fuckStack(actualScope,variableName) //variableScoping.peek()?.contains(variableName) //variableScoping.search(variableName)
        return tourStack(actualScope,variableName)
    }

    def private processVariableExpression(VariableExpression expression) {

        //println "name:${expression.name} - scope:${variableScoping.peek()} - isThis - ${expression.isThisExpression()}"
        if (variableScoping.peek().contains(expression.name) && !(actualScopeContains(expression.name))) {
            addScript("${GS_OBJECT}."+expression.name)
        } else if (variableStaticScoping.peek().contains(expression.name) && !(actualScopeContains(expression.name))) {
            addScript(translateClassName(classNameStack.peek())+'.'+expression.name)
        } else {
            if (processingClosure && !expression.isThisExpression()
                    && !allActualScopeContains(expression.name) && !variableScopingContains(expression.name)) {
                addScript('this.')
            }
            addScript(expression.name)
        }
    }

    /**
     *
     * @param b
     * @return
     */
    def private processBinaryExpression(BinaryExpression expression) {

        //println 'Binary->'+expression.text + ' - '+expression.operation.text
        //Getting a range from a list
        if (expression.operation.text=='[' && expression.rightExpression instanceof RangeExpression) {
            addScript('gSrangeFromList(')
            upgradedExpresion(expression.leftExpression)
            addScript(", ")
            visitNode(expression.rightExpression.getFrom())
            addScript(", ")
            visitNode(expression.rightExpression.getTo())
            addScript(')')
        //LeftShift function
        } else if (expression.operation.text=='<<') {
            //We call add function
            //println 'le->'+ expression.leftExpression
            addScript('gSmethodCall(')
            upgradedExpresion(expression.leftExpression)
            addScript(',"leftShift", gSlist([')
            upgradedExpresion(expression.rightExpression)
            addScript(']))')
        //Regular Expression exact match all
        } else if (expression.operation.text=='==~') {
            addScript('gSexactMatch(')
            upgradedExpresion(expression.leftExpression)
            addScript(',')
            //If is a regular expresion /fgsg/, comes like a contantExpresion fgsg, we keep /'s for javascript
            if (expression.rightExpression instanceof ConstantExpression) {
                addScript('/')
                processConstantExpression(expression.rightExpression,false)
                addScript('/')
            } else {
                upgradedExpresion(expression.rightExpression)
            }

            addScript(')')
        //A matcher of regular expresion
        } else if (expression.operation.text=='=~') {
            addScript('gSregExp(')
            //println 'rx->'+expression.leftExpression
            upgradedExpresion(expression.leftExpression)
            addScript(',')
            //If is a regular expresion /fgsg/, comes like a contantExpresion fgsg, we keep /'s for javascript
            if (expression.rightExpression instanceof ConstantExpression) {
                addScript('/')
                processConstantExpression(expression.rightExpression,false)
                addScript('/')
            } else {
                upgradedExpresion(expression.rightExpression)
            }

            addScript(')')
        //Equals
        } else if (expression.operation.text=='==') {
                addScript('gSequals(')
                upgradedExpresion(expression.leftExpression)
                addScript(', ')
                upgradedExpresion(expression.rightExpression)
                addScript(')')
        //in
        } else if (expression.operation.text=='in') {
            addScript('gSin(')
            upgradedExpresion(expression.leftExpression)
            addScript(', ')
            upgradedExpresion(expression.rightExpression)
            addScript(')')
        //Spaceship operator <=>
        } else if (expression.operation.text=='<=>') {
            addScript('gSspaceShip(')
            upgradedExpresion(expression.leftExpression)
            addScript(', ')
            upgradedExpresion(expression.rightExpression)
            addScript(')')
        //instanceof
        } else if (expression.operation.text=='instanceof') {
            addScript('gSinstanceOf(')
            upgradedExpresion(expression.leftExpression)
            addScript(', "')
            upgradedExpresion(expression.rightExpression)
            addScript('")')
        //Multiply
        } else if (expression.operation.text=='*') {
            addScript('gSmultiply(')
            upgradedExpresion(expression.leftExpression)
            addScript(', ')
            upgradedExpresion(expression.rightExpression)
            addScript(')')
        //Plus
        } else if (expression.operation.text=='+') {
            addScript('gSplus(')
            upgradedExpresion(expression.leftExpression)
            addScript(', ')
            upgradedExpresion(expression.rightExpression)
            addScript(')')
        //Minus
        } else if (expression.operation.text=='-') {
            addScript('gSminus(')
            upgradedExpresion(expression.leftExpression)
            addScript(', ')
            upgradedExpresion(expression.rightExpression)
            addScript(')')
        } else {

            //Execute setter if available
            if (expression.leftExpression instanceof PropertyExpression &&
                    (expression.operation.text in ['=','+=','-=']) &&
                !(expression.leftExpression instanceof AttributeExpression)) {
                    //(expression.leftExpression instanceof PropertyExpression && !expression.leftExpression instanceof AttributeExpression)) {

                PropertyExpression pe = (PropertyExpression)expression.leftExpression
                //println 'pe->'+pe.propertyAsString
                addScript('gSsetProperty(')
                upgradedExpresion(pe.objectExpression)
                addScript(',')
                //addScript(pe.propertyAsString)
                upgradedExpresion(pe.property)
                addScript(',')
                if (expression.operation.text == '+=') {
                    processPropertyExpression(expression.leftExpression)
                    addScript(' + ')
                } else if (expression.operation.text == '-=') {
                    processPropertyExpression(expression.leftExpression)
                    addScript(' - ')
                }
                upgradedExpresion(expression.rightExpression)
                addScript(')')

            } else {
                //println ' other->'+expression.text
                //If we are assigning a variable, and don't exist in scope, we add to it
                if (expression.operation.text=='=' && expression.leftExpression instanceof VariableExpression
                    && !allActualScopeContains(expression.leftExpression.name) &&
                        !variableScopingContains(expression.leftExpression.name)) {
                    addToActualScope(expression.leftExpression.name)
                }

                //If is a boolean operation, we have to apply groovyTruth
                //Left
                if (expression.operation.text in ['&&','||']) {
                    addScript '('
                    handExpressionInBoolean(expression.leftExpression)
                    addScript ')'
                } else {
                    upgradedExpresion(expression.leftExpression)
                }
                //Operator
                //println 'Operator->'+expression.operation.text
                addScript(' '+expression.operation.text+' ')
                //Right
                //println 'Right->'+expression.rightExpression
                if (expression.operation.text in ['&&','||']) {
                    addScript '('
                    handExpressionInBoolean(expression.rightExpression)
                    addScript ')'
                } else {
                    upgradedExpresion(expression.rightExpression)
                }
                if (expression.operation.text=='[') {
                    addScript(']')
                }
            }
        }
    }

    //Adding () for operators order, can spam loads of ()
    def private upgradedExpresion(expresion) {
        if (expresion instanceof BinaryExpression) {
            addScript('(')
        }
        visitNode(expresion)
        if (expresion instanceof BinaryExpression) {
            addScript(')')
        }
    }

    def private processConstantExpression(ConstantExpression expression) {
        //println 'ConstantExpression->'+expression.text
        if (expression.value instanceof String) {
            //println 'Value->'+expression.value+'<'+expression.value.endsWith('\n')
            def String value = ''
            if (expression.value.startsWith('\n')) {
                value = '\\n'
            }
            //if (expression.value.size()>0 && expression.value.endsWith('\n') && !value.endsWith('\n')) {
            //    value += '\\n'
            //}
            def list = []
            expression.value.eachLine {
                if (it) list << it
            }
            value += list.join('\\n')
            //expression.value.eachLine { if (it) value += it }
            //println 'Before->'+value
            //value = value.replaceAll('"','\\\\u0022')
            value = value.replaceAll('"','\\\\"')
            //println 'After->'+value+'<'+value.endsWith('\n')
            if (expression.value.endsWith('\n') && !value.endsWith('\n')) {
                value += '\\n'
            }
            addScript('"'+value+'"')
        } else {
            addScript(expression.value)
        }

    }

    def private processConstantExpression(ConstantExpression expression,boolean addStuff) {
        if (expression.value instanceof String && addStuff) {
            processConstantExpression(expression)
        } else {
            addScript(expression.value)
        }

    }

    /**
     * Finally GString is something like String + Value + String + Value + String....
     * So we convert to "  " + value + "    " + value ....
     * @param e
     * @return
     */
    def private processGStringExpression(GStringExpression expression) {

        def number = 0
        expression.getStrings().each {   exp ->
            if (number>0) {
                addScript(' + ')
            }
            //addScript('"')
            visitNode(exp)
            //addScript('"')

            if (expression.getValues().size() > number) {
                addScript(' + (')
                visitNode(expression.getValue(number))
                addScript(')')
            }
            number++
        }
    }

    def private processNotExpression(NotExpression expression) {
        addScript('!')
        visitNode(expression.expression)
    }

    def private processConstructorCallExpression(ConstructorCallExpression expression) {

        //println 'ConstructorCallExpression->'+expression.type.name + ' super? '+expression?.isSuperCall()
        //Super expression in constructor is allowed
        if (expression?.isSuperCall()) {
            def name = superNameStack.peek()
            //println 'processNotExpression name->'+name
            if (name == 'java.lang.Object') {
                addScript('this.gSconstructor')
            } else {
                addScript("this.${name}${expression.arguments.expressions.size()}")
            }
        } else if (expression.type.name=='java.util.Date') {
            addScript('gSdate')
        } else if (expression.type.name=='groovy.util.Expando') {
            addScript('gSexpando')
        } else if (expression.type.name=='java.util.Random') {
            addScript('gSrandom')
        } else if (expression.type.name=='java.util.HashSet') {
            addScript('gSset')
        } else if (expression.type.name=='java.lang.StringBuffer') {
            addScript('gSstringBuffer')
        } else {
            //println 'processConstructorCallExpression->'+ expression.type.name
            if (expression.type.name.startsWith('java.') || expression.type.name.startsWith('groovy.util.')) {
                throw new Exception('Not support type '+expression.type.name)
            }
            //Constructor have name with number of params on it
            //addScript("gsCreate${expression.type.name}().${expression.type.name}${expression.arguments.expressions.size()}")
            def name = translateClassName(expression.type.name)
            //addScript("gsCreate${name}")
            addScript(name)
        }
        visitNode(expression.arguments)
    }

    def private processArgumentListExpression(ArgumentListExpression expression,boolean withParenthesis) {
        if (withParenthesis) {
            addScript '('
        }
        int count = expression?.expressions?.size()
        expression.expressions?.each {
            visitNode(it)
            count--
            if (count) addScript ', '
        }
        if (withParenthesis) {
            addScript ')'
        }

    }

    def private processArgumentListExpression(ArgumentListExpression expression) {
        processArgumentListExpression(expression,true)
    }

    def private processObjectExpressionFromProperty(PropertyExpression expression) {
        if (expression.objectExpression instanceof ClassExpression) {
            addScript(translateClassName(expression.objectExpression.type.name))
        } else {
            visitNode(expression.objectExpression)
        }
    }

    def private processPropertyExpressionFromProperty(PropertyExpression expression) {
        if (expression.property instanceof GStringExpression) {
            visitNode(expression.property)
        } else {
            addScript('"')
            "process${expression.property.class.simpleName}"(expression.property,false)
            addScript('"')
        }
    }

    def private processPropertyExpression(PropertyExpression expression) {

        //println 'Pe->'+expression.objectExpression
        //println 'Pro->'+expression.property

        //If metaClass property we ignore it, javascript permits add directly properties and methods
        if (expression.property instanceof ConstantExpression && expression.property.value == 'metaClass') {
            if (expression.objectExpression instanceof VariableExpression) {

                if (expression.objectExpression.name=='this') {
                    addScript('this')
                } else {

                    //I had to add variable = ... cause gSmetaClass changing object and sometimes variable don't change
                    addScript("(${expression.objectExpression.name} = gSmetaClass(")
                    visitNode(expression.objectExpression)
                    addScript('))')
                }
            } else {
                if (expression.objectExpression instanceof ClassExpression &&
                    (expression.objectExpression.type.name.startsWith('java.') ||
                     expression.objectExpression.type.name.startsWith('groovy.'))) {
                    throw new Exception("Not allowed access metaClass of Groovy or Java types (${expression.objectExpression.type.name})")
                }
                addScript('gSmetaClass(')
                visitNode(expression.objectExpression)
                addScript(')')
            }
        } else if (expression.property instanceof ConstantExpression && expression.property.value == 'class') {
            visitNode(expression.objectExpression)
            addScript('.gSclass')
        } else {

            if (!(expression instanceof AttributeExpression)) {
                //println 'expr->'+expression
                addScript('gSgetProperty(')

                if (expression.objectExpression instanceof VariableExpression &&
                        expression.objectExpression.name=='this') {
                    addScript("gSthisOrObject(this,${GS_OBJECT})")
                } else {
                    processObjectExpressionFromProperty(expression)
                }

                addScript(',')

                processPropertyExpressionFromProperty(expression)

                //If is a safe expresion as item?.data, we add one more parameter
                if (expression.isSafe()) {
                    addScript(',true')
                }

                addScript(')')
            } else {

                processObjectExpressionFromProperty(expression)
                addScript('[')
                processPropertyExpressionFromProperty(expression)
                addScript(']')
            }
        }

    }

    def private processMethodCallExpression(MethodCallExpression expression) {

        //println "MCE ${expression.objectExpression} - ${expression.methodAsString}"
        def addParameters = true

        //Change println for javascript function
        if (expression.methodAsString == 'println' || expression.methodAsString == 'print') {
            addScript(printlnFunction)
        //Remove call method call from closures
        } else if (expression.methodAsString == 'call') {
            //println 'Calling!->'+expression.objectExpression

            if (expression.objectExpression instanceof VariableExpression) {
                addParameters = false
                def nameFunc = expression.objectExpression.text
                addScript("(${nameFunc}.delegate!=undefined?gSapplyDelegate(${nameFunc},${nameFunc}.delegate,[")
                processArgumentListExpression(expression.arguments,false)
                addScript("]):${nameFunc}")
                visitNode(expression.arguments)
                addScript(")")
            } else {
                visitNode(expression.objectExpression)
            }
        //Dont use dot(.) in super calls
        } else if (expression.objectExpression instanceof VariableExpression &&
                expression.objectExpression.name=='super') {
            addScript("${superMethodBegin}${expression.methodAsString}")
        //Function times, with a number, have to put (number) in javascript
        } else if (['times','upto','step'].contains(expression.methodAsString) && expression.objectExpression instanceof ConstantExpression) {
            addScript('(')
            visitNode(expression.objectExpression)
            addScript(')')
            addScript(".${expression.methodAsString}")
        //With
        } else if (expression.methodAsString == 'with' && expression.arguments instanceof ArgumentListExpression &&
                expression.arguments.getExpression(0) && expression.arguments.getExpression(0) instanceof ClosureExpression) {
            visitNode(expression.objectExpression)
            addScript(".gSwith")
        //Using Math library
        } else if (expression.objectExpression instanceof ClassExpression && expression.objectExpression.type.name=='java.lang.Math') {
            addScript("Math.${expression.methodAsString}")
        //Adding class.forName
        } else if (expression.objectExpression instanceof ClassExpression && expression.objectExpression.type.name=='java.lang.Class' &&
                expression.methodAsString=='forName') {
            addScript('gSclassForName(')
            //println '->'+expression.arguments[0]
            processArgumentListExpression(expression.arguments,false)
            addScript(')')
            addParameters = false
        //this.use {} Categories
        } else if (expression.objectExpression instanceof VariableExpression &&
                expression.objectExpression.name=='this' && expression.methodAsString == 'use') {
            //println 'Category going!'
            ArgumentListExpression args = expression.arguments
            //println 'cat size()->'+args.expressions.size()
            //println '0->'+ args.expressions[0].type.name

            addParameters = false
            addScript('gScategoryUse("')
            addScript(translateClassName(args.expressions[0].type.name))
            addScript('",')
            visitNode(args.expressions[1])
            addScript(')')
        //Mixin Classes
        } else if (expression.objectExpression instanceof ClassExpression && expression.methodAsString == 'mixin') {
            //println 'Mixin!'
            addParameters = false
            addScript("gSmixinClass('${translateClassName(expression.objectExpression.type.name)}',")
            addScript('[')
            ArgumentListExpression args = expression.arguments
            addScript args.expressions.inject ([]) { item,expr->
                item << '"'+translateClassName(expr.type.name)+'"'
            }.join(',')
            addScript('])')
        //Mixin Objects
        } else if (expression.objectExpression instanceof PropertyExpression &&
                expression.objectExpression.property instanceof ConstantExpression &&
                expression.objectExpression.property.text == 'metaClass' &&
                expression.methodAsString == 'mixin') {
            addParameters = false
            addScript("gSmixinObject(${expression.objectExpression.objectExpression.text},")
            addScript('[')
            ArgumentListExpression args = expression.arguments
            addScript args.expressions.inject ([]) { item,expr->
                item << '"'+translateClassName(expr.type.name)+'"'
            }.join(',')
            addScript('])')
        //Spread method call [1,2,3]*.toString()
        } else if (expression.isSpreadSafe()) {
            //println 'spreadsafe!'
            addParameters = false
            visitNode(expression.objectExpression)
            addScript(".collect(function(it) { return gSmethodCall(it,'${expression.methodAsString}',gSlist([")
            processArgumentListExpression(expression.arguments,false)
            addScript(']));})')
        } else {


            //println 'Method->'+expression.methodAsString+' - '+expression.arguments.class.simpleName
            addParameters = false

            addScript('gSmethodCall(')
            //Object
            if (expression.objectExpression instanceof VariableExpression &&
                    expression.objectExpression.name == 'this' &&
                    variableScoping.peek()?.contains(expression.methodAsString)) {
                //Remove this and put ${GS_OBJECT} for variable scoping
                addScript(GS_OBJECT)
            } else {
                visitNode(expression.objectExpression)
            }

            addScript(',')
            //MethodName
            visitNode(expression.method)

            addScript(',gSlist([')
            //Parameters
            "process${expression.arguments.class.simpleName}"(expression.arguments,false)

            addScript(']))')
        }

        if (addParameters) {
            visitNode(expression.arguments)
        }
    }

    def private processPostfixExpression(PostfixExpression expression) {

        if (expression.expression instanceof PropertyExpression) {

            //Only in mind ++ and --
            def plus = true
            if (expression.operation.text=='--') {
                plus = false
            }
            addScript('gSplusplus(')
            processObjectExpressionFromProperty(expression.expression)
            addScript(',')
            processPropertyExpressionFromProperty(expression.expression)
            addScript(",${plus},false)")
        } else {

            visitNode(expression.expression)
            addScript(expression.operation.text)

        }
    }

    def private processPrefixExpression(PrefixExpression expression) {
        if (expression.expression instanceof PropertyExpression) {
            def plus = true
            if (expression.operation.text=='--') {
                plus = false
            }
            addScript('gSplusplus(')
            processObjectExpressionFromProperty(expression.expression)
            addScript(',')
            processPropertyExpressionFromProperty(expression.expression)
            addScript(",${plus},true)")
        } else {
            addScript(expression.operation.text)
            visitNode(expression.expression)
        }
    }

    def private processReturnStatement(ReturnStatement statement) {
        //variableScoping.peek().add(gSgotResultStatement)
        returnScoping.add(true)
        addScript('return ')
        visitNode(statement.expression)
    }

    def private processClosureExpression(ClosureExpression expression) {
        processClosureExpression(expression, true)
    }

    def private processClosureExpression(ClosureExpression expression, boolean addItDefault) {

        addScript("function(")

        processingClosure = true
        putFunctionParametersAndBody(expression,false,addItDefault)
        processingClosure = false

        indent--
        //actualScope = []
        removeTabScript()
        addScript('}')

    }

    def private processIfStatement(IfStatement statement) {
        addScript('if (')
        visitNode(statement.booleanExpression)
        addScript(') {')
        indent++
        addLine()
        if (statement.ifBlock instanceof BlockStatement) {
            processBlockStament(statement.ifBlock,false)
        } else {
            //println 'if2->'+ statement.ifBlock.text
            visitNode(statement.ifBlock)
            addLine()
        }

        indent--
        removeTabScript()
        addScript('}')
        if (statement.elseBlock && !(statement.elseBlock instanceof EmptyStatement)) {
            //println 'Else->'+statement.elseBlock.text
            addScript(' else {')
            indent++
            addLine()
            if (statement.elseBlock instanceof BlockStatement) {
                processBlockStament(statement.elseBlock,false)
            } else {
                //println 'if2->'+ statement.ifBlock.text
                visitNode(statement.elseBlock)
                addLine()
            }
            indent--
            removeTabScript()
            addScript('}')
        }
    }

    def private processMapExpression(MapExpression expression) {
        addScript('gSmap()')
        expression.mapEntryExpressions?.each { ep ->
            addScript(".add(");
            visitNode(ep.keyExpression)
            addScript(",");
            visitNode(ep.valueExpression)
            addScript(")");
        }
    }

    def private processListExpression(ListExpression expression) {
        addScript('gSlist([')
        //println 'List->'+expression
        //l.each { println it}
        def first = true
        expression?.expressions?.each { it ->
            if (!first) {
                addScript(' , ')
            } else {
                first = false
            }
            visitNode(it)
        }
        addScript('])')
    }

    def private processRangeExpression(RangeExpression expression) {
        addScript('gSrange(')

        //println 'Is inclusive->'+r.isInclusive()
        visitNode(expression.from)
        addScript(", ")
        visitNode(expression.to)
        addScript(', '+expression.isInclusive())
        addScript(')')
    }

    def private processForStatement(ForStatement statement) {

        if (statement?.variable != ForStatement.FOR_LOOP_DUMMY) {
            //println 'DUMMY!-'+statement.variable
            //We change this for in...  for a call lo closure each, that works fine in javascript
            //"process${statement.variable.class.simpleName}"(statement.variable)
            //addScript ' in '

            visitNode(statement?.collectionExpression)
            addScript('.each(function(')
            visitNode(statement.variable)

        } else {
            addScript 'for ('
            //println 'collectionExpression-'+ statement?.collectionExpression.text
            visitNode(statement?.collectionExpression)
        }
        addScript ') {'
        indent++
        addLine()

        visitNode(statement?.loopBlock)

        indent--
        removeTabScript()
        addScript('}')
        if (statement?.variable != ForStatement.FOR_LOOP_DUMMY) {
            addScript(')')
        }
    }

    def private processClosureListExpression(ClosureListExpression expression) {
        //println 'ClosureListExpression-'+expression.text
        boolean first = true
        expression?.expressions?.each { it ->
            if (!first) {
                addScript(' ; ')
            }
            first = false
            visitNode(it)
        }
    }

    def private processParameter(Parameter parameter) {
        //println 'Initial->'+parameter.getInitialExpression()
        addScript(parameter.name)
    }

    def private processTryCatchStatement(TryCatchStatement statement) {
        //Try block
        addScript('try {')
        indent++
        addLine()

        visitNode(statement?.tryStatement)

        indent--
        removeTabScript()
        //Catch block
        addScript('} catch (')
        if (statement?.catchStatements[0]) {
            visitNode(statement?.catchStatements[0].variable)
        } else {
            addScript('e')
        }
        addScript(') {')
        indent++
        addLine()
        //Only process first catch
        visitNode(statement?.catchStatements[0])

        indent--
        removeTabScript()
        addScript('}')
    }

    def private processCatchStatement(CatchStatement statement) {
        processBlockStament(statement.code,false)
    }

    def private processTernaryExpression(TernaryExpression expression) {
        //println 'Ternary->'+expression.text
        addScript('(')
        visitNode(expression.booleanExpression)
        addScript(' ? ')
        visitNode(expression.trueExpression)
        addScript(' : ')
        visitNode(expression.falseExpression)
        addScript(')')
    }

    def private getSwitchExpression(Expression expression,String varName) {

        if (expression instanceof ClosureExpression) {
            addClosureSwitchInitialization = true
            processClosureExpression(expression,true)
            addScript('()')
        } else {
            addScript("${varName} === ")
            visitNode(expression)
        }

    }

    def private processSwitchStatement(SwitchStatement statement) {

        def varName = 'gSswitch' + switchCount++

        addScript('var '+varName+' = ')
        visitNode(statement.expression)
        addScript(';')
        addLine()

        def first = true

        statement.caseStatements?.each { it ->
            if (first) {
                addScript("if (")
                first = false
            } else {
                addScript("} else if (")
            }
            getSwitchExpression(it.expression,varName)
            addScript(') {')
            indent++
            addLine()
            visitNode(it?.code)
            indent--
            removeTabScript()
        }
        if (statement.defaultStatement) {
            addScript('} else {')
            indent++
            addLine()
            visitNode(statement.defaultStatement)
            indent--
            removeTabScript()
        }

        addScript('}')

        switchCount--
    }

    def private processCaseStatement(CaseStatement statement) {
        addScript 'case '
        visitNode(statement?.expression)
        addScript ':'
        indent++
        addLine()
        visitNode(statement?.code)
        indent--
        removeTabScript()
    }

    def private processBreakStatement(BreakStatement statement) {
        if (switchCount==0) {
            addScript('break')
        }
    }

    def private processWhileStatement(WhileStatement statement) {
        addScript('while (')
        visitNode(statement.booleanExpression)
        addScript(') {')
        indent++
        addLine()
        visitNode(statement.loopBlock)
        indent--
        removeTabScript()
        addScript('}')
    }

    def private processTupleExpression(TupleExpression expression, withParenthesis = true) {
        //println 'Tuple->'+expression.text
        if (withParenthesis) {
            addScript('(')
        }
        addScript('gSmap()')
        expression.expressions.each {
            visitNode(it)
            if (withParenthesis) {
                addScript(')')
            }
        }
    }

    def private processNamedArgumentListExpression(NamedArgumentListExpression expression) {
        expression.mapEntryExpressions.eachWithIndex { MapEntryExpression exp,i ->
            //println 'key->'+ exp.keyExpression
            addScript('.add(')
            visitNode(exp.keyExpression)
            addScript(',')
            visitNode(exp.valueExpression)
            addScript(')')
        }
    }

    def private processBitwiseNegationExpression(BitwiseNegationExpression expression) {
        addScript("/${expression.text}/")
    }

    def private processEnum(ClassNode node) {

        addLine()

        //Push name in stack
        variableScoping.push([])

        addScript("var ${translateClassName(node.name)} = {")

        indent ++
        addLine()

        //addLine()
        //ignoring generics and interfaces and extends atm
        //visitGenerics node?.genericsTypes
        //node.interfaces?.each {
        //visitType node.superClass

        //Fields
        def numero = 1
        node?.fields?.each { it->
            if (!['MIN_VALUE','MAX_VALUE','$VALUES'].contains(it.name)) {
                addScript("${it.name} : ${numero++},")
                addLine()
                variableScoping.peek().add(it.name)
            }
        }

        //Methods
        node?.methods?.each { //println 'method->'+it;

            if (!['values','next','previous','valueOf','$INIT','<clinit>'].contains(it.name)) {

                variableScoping.peek().add(it.name)
                addScript("${it.name} : function(")
                putFunctionParametersAndBody(it,false,true)

                indent--
                removeTabScript()
                addScript('},')
                addLine()

            }
        }

        indent --
        addLine()
        addScript('}')
        addLine()

        //Remove variable class names from the list
        variableScoping.pop()
    }

    def private processClassExpression(ClassExpression expression) {
        addScript(translateClassName(expression.text))
    }

    def private processThrowStatement(ThrowStatement statement) {
        addScript('throw "Exception"')
        //println 'throw expression'+statement.expression.text
    }

    def private processStaticMethodCallExpression(StaticMethodCallExpression expression) {

        //println 'SMCE->'+expression.text
        addScript("${expression.ownerType.name}.${expression.method}")
        visitNode(expression.arguments)
    }

    def private processElvisOperatorExpression(ElvisOperatorExpression expression) {
        //println 'Elvis->'+expression.text
        //println 'true->'+expression.trueExpression
        //println 'false->'+expression.falseExpression

        addScript('gSelvis(')
        visitNode(expression.booleanExpression)
        addScript(' , ')
        visitNode(expression.trueExpression)
        addScript(' , ')
        visitNode(expression.falseExpression)
        addScript(')')

    }

    def private processAttributeExpression(AttributeExpression expression) {
        processPropertyExpression(expression)
    }

    def private processCastExpression(CastExpression expression) {
        if (expression.type.nameWithoutPackage == 'Set' && expression.expression instanceof ListExpression) {
            addScript('gSset(')
            visitNode(expression.expression)
            addScript(')')
        } else {
            throw new Exception('Casting not supported for '+expression.type.name)
        }
    }

    def private processMethodPointerExpression(MethodPointerExpression expression) {
        //println 'Exp-'+expression.expression
        //println 'dynamic-'+expression.dynamic
        //println 'methodName-'+expression.methodName
        visitNode(expression.expression)
        addScript('[')
        visitNode(expression.methodName)
        addScript(']')
    }

    def private processSpreadExpression(SpreadExpression expression) {
        //println 'exp-'+expression
        addScript('new GSspread(')
        visitNode(expression.expression)
        addScript(')')
    }

    def private processSpreadMapExpression(SpreadMapExpression expression) {
        //println 'Map exp-'+expression.text
        addScript('"gSspreadMap"')
    }

    def private processEmptyExpression(EmptyExpression expression) {
        //Nothing to do
    }

    private void visitNode(expression) {
        "process${expression.class.simpleName}"(expression)
    }
}
