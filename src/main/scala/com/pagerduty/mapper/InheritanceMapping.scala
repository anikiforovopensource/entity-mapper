package com.pagerduty.mapper

import com.pagerduty.mapper.UntypedEntityMapping._
import java.lang.annotation.Annotation
import java.util.logging.Logger
import scala.annotation.tailrec


/**
 * Maps single table inheritance (STI) using discriminator column.
 *
 * The target class must be annotated as @Superclass(subclasses). Only provided subclasses can
 * be used with the inheritance mapping. Each subclass must have @Discriminator annotation.
 * @Superclass annotation will be ignore for all the subclasses, so it is possible to have
 * multiple inheritance mappings that service different inheritance subtrees.
 */
private[mapper] class InheritanceMapping(
    val target: Class[_],
    val name: Option[String],
    val ttlSeconds: Option[Int],
    val registeredSerializers: Map[Class[_], Any],
    val customMappers: Map[Class[_ <: Annotation], Mapping => Mapping])
  extends UntypedEntityMapping
{
  import UntypedEntityMapping._
  import InheritanceMapping.log

  /**
   * ClassMapping for each subclass.
   */
  protected val mappingByClassName: Map[String, (String, ClassMapping)] = {
    def classMapping(subclass: Class[_]): (String, ClassMapping) = {
      val discriminatorAnnotation = subclass.getAnnotation(DiscriminatorAnnotationClass)
      if (discriminatorAnnotation == null) {
        throw new EntityMapperException(s"Class ${subclass.getName} " +
          s"must have @Discriminator annotation, because it is a part of ${target.getName} " +
          "inheritance mapping.")
      }
      val discriminator = discriminatorAnnotation.value
      val ttlOp = Option(target.getAnnotation(TtlAnnotationClass)).map(_.seconds)
      (discriminator, new ClassMapping(subclass, name, ttlOp, registeredSerializers, customMappers))
    }

    target.getAnnotation(SuperclassAnnotationClass).subclasses.map { subclass =>
      subclass.getName -> classMapping(subclass)
    }.toMap
  }
  protected val mappingByDiscriminator: Map[String, ClassMapping] = {
    val classesByDiscriminator = mappingByClassName.toSeq
      .groupBy { case (_, (disciminator, _)) => disciminator }
      .mapValues(_.map { case (clazz, _) => clazz })

    classesByDiscriminator.find { case (discriminator, classes) => classes.size > 1 } match {
      case Some((discriminator, classes)) =>
        throw new EntityMapperException(s"Classes ${classes.mkString(", ")} " +
          s"have the same @Discriminator value '$discriminator'.")
      case None =>
        // ignore
    }

    mappingByClassName.map { case (_, (disciminator, mapping)) => disciminator -> mapping }
  }
  protected val allMappings: Seq[ClassMapping] = mappingByDiscriminator.values.toSeq

  protected def mappingFor(clazz: Class[_]): (String, ClassMapping) = {
    val className = if (clazz.getName.endsWith("$")) clazz.getSuperclass.getName else clazz.getName
    mappingByClassName.getOrElse(className, throw new EntityMapperException(
      s"Class ${clazz.getName} is not part of the ${target.getName} inheritance mapping."))
  }

  protected val discriminatorMapping: FieldMapping = {
    val discriminatorColumn = target.getAnnotation(SuperclassAnnotationClass).discriminatorColumn
    val stringSerializer = registeredSerializers(classOf[String])
    new FieldMapping(stringSerializer, prefixed(discriminatorColumn))
  }


  val isIdDefined: Boolean = allMappings.forall(_.isIdDefined)
  def getId(entity: Any): Any = mappingFor(entity.getClass)._2.getId(entity)
  def setId(entity: Any, id: Any): Unit = mappingFor(entity.getClass)._2.setId(entity, id)

  val serializersByColName: Seq[(String, Any)] = {
    val schema = allMappings.flatMap(_.serializersByColName).toSet
    val reserved = schema.find { case (colName, _) => colName == discriminatorMapping.name }
    if (reserved.isDefined) {
      throw new EntityMapperException(s"Column name '${reserved.get._1}' " +
        s"is reserved as discriminator column name in ${target.getName} inheritance mapping.")
    }
    validateSchema(target, schema)
    schema.toSeq ++ discriminatorMapping.serializersByColName
  }

  def write(
      targetId: Any, entity: Option[Any], mutation: MutationAdapter, ttlSeconds: Option[Int])
  : Unit = {
    if (entity.isDefined) {
      val (discriminator, mapping) = mappingFor(entity.get.getClass)
      // Remove all the columns from other mappings.
      allMappings.filterNot(_ == mapping).foreach {
        _.write(targetId, None, mutation, mapping.fieldNames, None)
      }
      // Write discriminator.
      discriminatorMapping.write(targetId, Some(discriminator), mutation, ttlSeconds)
      // Write mapping.
      mapping.write(targetId, entity, mutation, ttlSeconds)
    }
    else {
      discriminatorMapping.write(targetId, None, mutation, None)
      allMappings.foreach(_.write(targetId, None, mutation, None))
    }
  }

  def read(targetId: Any, result: ResultAdapter): MappedResult = {
    discriminatorMapping.read(targetId, result).flatMap { case discriminator: String =>
      mappingByDiscriminator.get(discriminator).map(_.readDefined(targetId, result)).getOrElse {
        log.severe(s"Not found mapping for discriminator '$discriminator' " +
          s"for ${target.getName} inheritance mapping for entity " +
          s"'$targetId'.")
        Undefined
      }
    }
  }

  override def toString(): String = s"InheritanceMapping(${target.getName}, $name)"
}


private[mapper] object InheritanceMapping {
  val log = Logger.getLogger(classOf[InheritanceMapping].getName)

  @tailrec def isPartOfInheritanceMapping(target: Class[_]): Boolean = {
    if (target.getAnnotation(SuperclassAnnotationClass) != null) true
    else if (target.getSuperclass == null) false
    else isPartOfInheritanceMapping(target.getSuperclass)
  }
}
