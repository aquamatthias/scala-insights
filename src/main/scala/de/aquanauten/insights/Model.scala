package de.aquanauten.insights

case class TypeHandle(
  name: String,
  fullName: String,
  typeArgs: List[TypeHandle]
) {
  def simple: String = {
    val targs = if (typeArgs.nonEmpty) typeArgs.map(_.simple).mkString("[", ", ", "]") else ""
    name + targs
  }
}

case class ValHandle(
  name: String,
  typeHandle: TypeHandle
)

case class MethodHandle(
  name: String,
  returnType: TypeHandle,
  params: Map[String, TypeHandle]
)

case class ClassHandle(
  packageName: String,
  name: String,
  fullName: String,
  typeParams: Seq[TypeHandle],
  baseClasses: Seq[TypeHandle],
  methods: Seq[MethodHandle],
  vals: Seq[ValHandle],
  dependencies: Seq[String],
  isObject: Boolean,
  isTrait: Boolean,
  isAbstract: Boolean
) {
  def fullTypedName: String = {
    val typeName = if (typeParams.isEmpty) "" else typeParams.map(_.fullName).mkString("[", ", ", "]")
    s"$fullName$typeName"
  }

  val kind = if (isObject) "object" else if (isTrait) "trait" else if (isAbstract) "abstract class" else "class"
}

case class CompileUnit( classes: Seq[ClassHandle] )

case class Dependencies( dependencies: List[(String, String)] )
