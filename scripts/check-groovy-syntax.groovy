/**
 * check-groovy-syntax.groovy: parse every Groovy file given on the command line
 * and report syntax errors. Used by scripts/validate.sh.
 *
 * It only PARSES (CompilePhase CONVERSION); it does not resolve classes. That
 * is on purpose: Jenkinsfiles and vars/ refer to Jenkins steps (sh, checkout...)
 * and plugin classes that do not exist outside Jenkins. Real compilation and
 * behaviour are covered by the JenkinsPipelineUnit tests in test/.
 *
 * Usage: groovy scripts/check-groovy-syntax.groovy <file>...
 */
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases

int failures = 0
args.each { String path ->
    File file = new File(path)
    CompilationUnit unit = new CompilationUnit()
    unit.addSource(file)
    try {
        unit.compile(Phases.CONVERSION)
        println "OK    ${path}"
    } catch (CompilationFailedException e) {
        failures++
        println "FAIL  ${path}\n${e.message}"
    }
}
println "${args.length - failures}/${args.length} Groovy files parsed without syntax errors"
System.exit(failures ? 1 : 0)
