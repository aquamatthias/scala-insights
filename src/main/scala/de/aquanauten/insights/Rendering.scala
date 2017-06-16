package de.aquanauten.insights

trait Rendering[A] {
  def render(a: A): String
}

object Rendering {
  def render[A](a: A)(implicit rendering: Rendering[A]) = rendering.render(a)
}

object JsonRendering {

  implicit object DependencyRendering extends Rendering[Dependencies] {
    override def render(deps: Dependencies): String = {
      val dependencies = deps.dependencies.groupBy(_._1).map { case (pkg, pkgDeps) =>
        val dependencies = pkgDeps.toMap.values.map("\""+_+"\"").mkString(", ")
        s"""{ "package": "$pkg", "dependencies": [ $dependencies ]  }"""
      }
      s"""{ "dependencies": [ ${dependencies.mkString(", ")} ] }"""
    }
  }

  implicit object TypeHandleRendering extends Rendering[TypeHandle] {
    override def render(value: TypeHandle): String = {
      import value._
      s"""{ "name": "$name", "fullName": "$fullName", "typeArgs": [${typeArgs.map(render).mkString(", ")}] }"""
    }
  }

  implicit object ValHandleRendering extends Rendering[ValHandle] {
    override def render(value: ValHandle): String = {
      s"""{ "name": "${value.name}", "type": ${Rendering.render(value.typeHandle)} }"""
    }
  }

  implicit object MethodHandleRendering extends Rendering[MethodHandle] {
    override def render(method: MethodHandle): String = {
      import method._
      def paramJson(entry: (String, TypeHandle)): String = s"""{ "name": "${entry._1}", "type": ${Rendering.render(entry._2)} }"""
      s"""{ "name": "$name", "returnType": ${Rendering.render(returnType)}, "params": [ ${params.map(paramJson).mkString(", ")} ] } """
    }
  }

  implicit object ClassHandleRendering extends Rendering[ClassHandle] {
    override def render(handle: ClassHandle): String = {
      import handle._
      s"""{"kind": "$kind",
         | "name": "$name",
         | "packageName": "$packageName",
         | "fullname": "$fullName",
         | "fullTypedName": "$fullTypedName",
         | "typeParams": [${typeParams.map(Rendering.render(_)).mkString(", ")}],
         | "extends": [${baseClasses.map(Rendering.render(_)).mkString(", ")}],
         | "isObject": $isObject,
         | "isTrait": $isTrait,
         | "isAbstract": $isAbstract,
         | "vals": [ ${vals.map(v => Rendering.render(v)).mkString(", ")} ],
         | "methods": [ ${methods.map(m => Rendering.render(m)).mkString(", ")} ],
         | "dependencies": [ ${dependencies.map("\""+_+"\"").mkString(", ")} ]
         |}""".stripMargin
    }
  }

  implicit object CompileUnitRendering extends Rendering[CompileUnit] {
    override def render(unit: CompileUnit): String = {
      s"""{ "classes": [ ${unit.classes.map( Rendering.render(_) ).mkString(", ")} ] }"""
    }
  }

  def render(result: CompileUnit) = Rendering.render(result)
}


object PlantUMLRendering {

  implicit object DependencyRendering extends Rendering[Dependencies] {
    override def render(deps: Dependencies): String = {
      val allPackages = deps.dependencies
        .foldLeft(Set.empty[String]) { case (result, (key, value)) => result + key + value }
        .map(dep => s"package $dep")
        .mkString("\n")
      val allDependencies = deps.dependencies
        .map({ case (key, value) => s"""$key ..> $value""" })
        .mkString("\n")
      s"""@startuml
         |$allPackages
         |$allDependencies
         |@enduml
       """.stripMargin
    }
  }

  implicit object ValHandleRendering extends Rendering[ValHandle] {
    override def render(value: ValHandle): String = s"  {field} -${value.name}: ${value.typeHandle.simple}"
  }

  implicit object MethodHandleRendering extends Rendering[MethodHandle] {
    override def render(method: MethodHandle): String = {
      val returnT = method.returnType.simple
      val returnString = if (returnT == "Unit") "" else ": " + returnT
      s"  {method} ${method.name}(${method.params.keys.mkString(", ")})$returnString"
    }
  }

  implicit object ClassHandleRendering extends Rendering[ClassHandle] {
    override def render(handle: ClassHandle): String = {
      def typeName = handle.fullName
      def dependencies = handle.dependencies.map(d => s"$typeName ..> $d")
      def extensions = handle.baseClasses.map(d => s"""${d.fullName} <|-- $typeName""")
      s"""class $typeName {
         |${handle.vals.map(Rendering.render(_)).mkString("\n")}
         |${handle.methods.map(Rendering.render(_)).mkString("\n")}
         |}
         |${dependencies.mkString("\n")}
         |${extensions.mkString("\n")}""".stripMargin
    }
  }

  implicit object CompileUnitRendering extends Rendering[CompileUnit] {
    override def render(unit: CompileUnit): String = {
      s"""
         |@startuml
         |${unit.classes.filter(!_.isObject).map(Rendering.render(_)).mkString("\n")}
         |@enduml
      """.stripMargin
    }
  }

  def render(result: CompileUnit) = Rendering.render(result)
}

object DotRendering {

  implicit object DependencyRendering extends Rendering[Dependencies] {
    override def render(deps: Dependencies): String = {
      val allDeps = deps.dependencies.map { case (key, value) => s""""$key" -> "$value";\n""" }.mkString("\n")
      s"""
         |digraph {
         |$allDeps
         |}
       """.stripMargin
    }
  }
}