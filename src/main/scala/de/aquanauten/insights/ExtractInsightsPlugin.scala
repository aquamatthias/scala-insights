package de.aquanauten.insights

import java.util.regex.Pattern

import scala.collection.mutable
import scala.tools.nsc.plugins.{Plugin, PluginComponent}
import scala.tools.nsc.{Global, Phase}

/**
  * Scala Compiler plugin, that extracts information about the compiled project.
  * Information gathered:
  * - Package dependency diagram
  * - Package type summary
  * - Class information summary
  *
  * This plugin creates files in plantuml (http://plantuml.com) format.
  *
  * This plugin can be configured via scala compiler command line parameter
  *
  */
class ExtractInsightsPlugin(override val global: Global) extends Plugin {

  import ExtractInsightsPlugin._

  // meta data for the scala compiler
  override val name: String = "insights"
  override val description: String = "Extract insights from the AST"
  override val components = List(ExtractInsightsPluginComponent)

  // Options of this plugin, set via -P:<name>:<value>
  var basePackage = Option.empty[String]
  var packageExcludes = Seq.empty[Pattern]
  var targetDir = "target/insight"
  var packageFile = "package_dependency"
  var classFile = "class_summary"
  var classExcludes = Seq.empty[Pattern]

  // Process Options is called from the scala compiler
  val KVOption = "^([^:]+)[:]?(.*)$".r

  override def processOptions(options: List[String], error: String => Unit) {
    def handle(key: String, value: String) = key match {
      case "basePackage" => basePackage = Some(value)
      case "targetDir" => targetDir = value
      case "packageFile" => packageFile = value
      case "packageExcludes" => packageExcludes = value.split(":").map(Pattern.compile).toSeq
      case "classFile" => classFile = value
      case "classExcludes" => classExcludes = value.split(":").map(Pattern.compile).toSeq
      case unknown => error(s"Unknown option >$unknown<")
    }

    options.foreach {
      case KVOption(key, value) => handle(key, value)
      case other => error(s"Wrong format for: >$other<. Use key:value.")
    }
  }

  object ExtractInsightsPluginComponent extends PluginComponent {
    override val phaseName: String = "extract-dependencies"
    override val runsAfter: List[String] = List("refchecks")
    override val global: Global = ExtractInsightsPlugin.this.global

    override def newPhase(prev: Phase): Phase = new ShowDependenciesPhase(prev)


    class ShowDependenciesPhase(prev: Phase) extends StdPhase(prev) {
      override def name = ExtractInsightsPlugin.this.name

      var packageDependencies = List.empty[(global.Symbol, global.Symbol)]
      var classSummary = List.empty[ClassHandle]

      /**
        * Executed for each phase run.
        */
      override def run(): Unit = {
        super.run()
        ResultWriter.writeDependencies(s"$targetDir/$packageFile", Dependencies(packageDependencies.distinct.map{case (k,v) => k.fullName -> v.fullName}.sortBy(_._1)) )
        ResultWriter.writeClasses(targetDir, classFile, CompileUnit(classSummary))
      }

      /**
        * Returns a package name to filter for.
        * Either a configured package or the root package of the element.
        */
      def packageFilterFor(element: global.Tree) = basePackage.getOrElse {
        def findRootPackage(symbol: global.Symbol): String = {
          if (!symbol.enclosingPackage.fullName.contains('.')) symbol.enclosingPackage.fullName
          else findRootPackage(symbol.enclosingPackage)
        }

        findRootPackage(element.symbol)
      }

      /**
        * Filter all dependencies for the given element in the given compilation unit.
        * - all dependencies to "foreign" packages (other root package) are filtered
        * - dependencies to self are filtered
        * - dependencies to parent packages are filtered.
        *
        * @param unit    the compilation unit
        * @param element the AST element
        * @return all filtered dependencies
        */
      def filteredDependencies(unit: global.CompilationUnit, element: global.Tree): mutable.HashSet[ExtractInsightsPluginComponent.global.Symbol] = {
        // filters the root package of current tree: foo.bla.bar --> foo
        val filterByPackage = packageFilterFor(element)

        // filter all dependencies of this unit
        unit.depends
          .filter(dep => dep.hasPackageFlag &&
            dep.fullName.startsWith(filterByPackage) && // filter out references according to the filter
            dep.fullName != element.symbol.fullName && // filter out references to self
            !element.symbol.fullName.startsWith(dep.fullName) && // filter out references to parents (might be wrong)
            !packageExcludes.exists(_.matcher(element.symbol.fullName).find()) // must pass all exclude filters
          )
      }

      /**
        * Extract the type handle for given tree element.
        */
      def typeHandle(typeT: global.Type): TypeHandle = {
        val args = typeT.typeArgs
        val typeArgs = args.map(typeHandle)
        val typeArgsString = if (args.isEmpty) "" else args.mkString("[", ", ", "]")
        val name = ShortMethodName(typeT.typeSymbol.fullName)
        TypeHandle(typeT.typeSymbol.name.toString.trim, name + typeArgsString, typeArgs)
      }

      /**
        * Extract the class handle for the given tree element.
        */
      def classHandle(unit: global.CompilationUnit, element: global.ImplDef, isObject: Boolean): ClassHandle = {
        val filterByPackage = packageFilterFor(element)
        val packageName = element.symbol.enclosingPackage.fullName

        val allUnitItems = unit.body.filter(e => e.isType && e.symbol.enclosingPackage.fullName == packageName).map(_.tpe.typeSymbol.fullName).toSet
        val dependencies = unit.depends
          .filter(dep =>
            dep.fullName.startsWith(filterByPackage) && // filter out references according to the filter
              dep.fullName != element.symbol.fullName && // filter out references to self
              !element.symbol.fullName.startsWith(dep.fullName) && // filter out references to parents (might be wrong)
              !allUnitItems.contains(dep.fullName) && // filter dependencies to this compile unit
              !dep.hasPackageFlag) // no package dependency
          .map(_.fullName)

        val properties = element.impl.tpe.decls.toSeq.filter(m => m.isVal).map { value =>
          ValHandle(value.name.toString.trim, typeHandle(value.tpe))
        }

        def allowedMethodName(name: String) = !name.contains("$") && !FilterMethodNames.contains(name) && !properties.exists(_.name == name)
        val methods = element.impl.tpe.nonPrivateDecls.filter(m => m.isMethod && allowedMethodName(m.name.toString)).flatMap(_.alternatives.filter(_.isInstanceOf[global.MethodSymbol]).asInstanceOf[List[global.MethodSymbol]])map { method =>
          val par = method.paramLists.flatMap(_.map { param =>
            param.name.toString -> typeHandle(param.typeSignature)
          })
          MethodHandle(method.name.toString.trim, typeHandle(method.returnType), par.toMap)
        }

        /* This does not work for overloaded methods
        val methods = element.impl.tpe.nonPrivateDecls.filter(m => m.isMethod && allowedMethodName(m.name.toString)).flatMap(_.asTerm.alternatives).map(_.asMethod).map { method =>
          val par = method.paramLists.flatMap(_.map { param =>
            param.name.toString -> typeHandle(param.typeSignature)
          })
          MethodHandle(method.name.toString.trim, typeHandle(method.returnType), par.toMap)
        }
        */

        val baseClasses = element.impl.tpe.baseClasses
          .filter(t =>
            !FilterTypes.contains(t.typeSignature.typeSymbol.fullName) && // Filter out types by filter
            !FilterTypesStartingWith.exists(t.typeSignature.typeSymbol.fullName.startsWith) &&
            t.typeSignature.typeSymbol.fullName != element.symbol.fullName // Filter out references to self
          )
          .map(base => typeHandle(base.typeSignature))

        ClassHandle(packageName,
          element.symbol.name.toString,
          element.symbol.fullName,
          element.symbol.tpe.typeArgs.map(typeHandle),
          baseClasses,
          methods,
          properties,
          dependencies.toSeq,
          isObject,
          element.impl.tpe.typeSymbol.isTrait,
          element.impl.tpe.typeSymbol.isAbstract
        )
      }

      /**
        * Called by the compiler with the given compilation unit.
        */
      override def apply(unit: global.CompilationUnit): Unit = {
        for {
          packageDef@global.PackageDef(_, _) <- unit.body
          dep <- filteredDependencies(unit, packageDef)
        } packageDependencies ::= packageDef.symbol -> dep

        def includeTree(tree: global.Tree) = {
          !classExcludes.exists(_.matcher(tree.symbol.fullName).find()) &&
          !tree.symbol.name.toString.contains("$") //synthetic types
        }
        for (module@global.ModuleDef(_, _, _) <- unit.body if includeTree(module)) {
          classSummary ::= classHandle(unit, module, isObject = true)
        }
        for (clazz@global.ClassDef(_, _, _, _) <- unit.body if includeTree(clazz)) {
          classSummary ::= classHandle(unit, clazz, isObject = false)
        }
      }
    }
  }
}

object ExtractInsightsPlugin {

  /**
    * Method names that should be filtered out:
    * - product methods
    * - default java methods (toString, equals, hashCode)
    * - case class generated methods (apply, unapply)
    */
  val FilterMethodNames = Set(
    "<init>",
    "apply",
    "unapply",
    "copy",
    "productPrefix",
    "productArity",
    "productElement",
    "productIterator",
    "canEqual",
    "hashCode",
    "toString",
    "equals",
    "readResolve"
  )

  val FilterTypes = Set(
    "java.lang.Object",
    "scala.Any",
    "scala.AnyVal",
    "scala.AnyRef",
    "scala.Equals",
    "scala.Serializable",
    "java.io.Serializable",
    "scala.Product"
  )

  val FilterTypesStartingWith = Set(
    "scala.Function",
    "scala.runtime.AbstractFunction",
    "scala.Tuple"
  )

  /**
    * Reverse the scala.Predef expansion.
    */
  val ShortMethodName = Map(
    "java.lang.String" -> "String",
    "scala.collection.Seq" -> "Seq",
    "scala.collection.immutable.List" -> "List",
    "scala.collection.immutable.Map" -> "Map",
    "scala.collection.immutable.Set" -> "Set",
    "scala.Int" -> "Int",
    "scala.Double" -> "Double",
    "scala.Float" -> "Float",
    "scala.Boolean" -> "Boolean"
  ).withDefault(identity)
}

