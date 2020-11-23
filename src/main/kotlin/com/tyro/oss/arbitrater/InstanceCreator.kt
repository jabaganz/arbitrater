/*
 * Copyright [2018] Tyro Payments Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tyro.oss.arbitrater

import com.tyro.oss.randomdata.RandomEnum
import io.github.classgraph.ClassGraph
import kotlin.reflect.*
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.withNullability
import kotlin.reflect.jvm.isAccessible

// TODO: Arrays?

private val wildcardMapType = Map::class.createType(arguments = listOf(KTypeProjection.STAR, KTypeProjection.STAR))
private val wildcardNullableMapType =
        Map::class.createType(arguments = listOf(KTypeProjection.STAR, KTypeProjection.STAR), nullable = true)
private val wildcardCollectionType = Collection::class.createType(arguments = listOf(KTypeProjection.STAR))
private val wildcardNullableCollectionType =
        Collection::class.createType(arguments = listOf(KTypeProjection.STAR), nullable = true)
private val wildcardListType = List::class.createType(arguments = listOf(KTypeProjection.STAR))
private val wildcardNullableListType = List::class.createType(arguments = listOf(KTypeProjection.STAR), nullable = true)
private val wildcardSetType = Set::class.createType(arguments = listOf(KTypeProjection.STAR))
private val wildcardNullableSetType = Set::class.createType(arguments = listOf(KTypeProjection.STAR), nullable = true)
private val wildcardEnumType = Enum::class.createType(arguments = listOf(KTypeProjection.STAR))
private val wildcardNullableEnumType = Enum::class.createType(arguments = listOf(KTypeProjection.STAR), nullable = true)

val classGraphScan = ClassGraph().enableClassInfo().scan()

class InstanceCreator<out T: Any>(private val targetClass: KClass<T>, settings: GeneratorSettings = GeneratorSettings())
    : ConfigurableArbitrater(settings, DefaultConfiguration.generators.toMutableMap()) {

    private val specificValues: MutableMap<KParameter, Any?> = mutableMapOf()

    fun generateNulls(value: Boolean = true): InstanceCreator<T> =
            InstanceCreator(targetClass, settings.copy(generateNulls = value))

    fun useDefaultValues(value: Boolean = false): InstanceCreator<T> =
            InstanceCreator(targetClass, settings.copy(useDefaultValues = value))

    fun withValue(parameterName: String, value: Any?): InstanceCreator<T> {
        targetClass.primaryConstructor?.parameters?.find { it.name == parameterName }?.let {
            specificValues[it] = value
        } ?: throw IllegalArgumentException(
                "Parameter named $parameterName not found in primary constructor of ${targetClass.simpleName}")

        return this
    }

    /**
     * Create an arbitrary instance, or else explode
     */
    fun createInstance(): T {

        try {
            return when {
                targetClass.isAbstract || targetClass.isSealed -> targetClass.getRandomSubclass()
                        .createInstanceWithGenerator() as T
                targetClass.objectInstance != null             -> targetClass.objectInstance!!
                else                                           -> {
                    validateConstructor()
                    val primaryConstructor = targetClass.primaryConstructor!!

                    val constructorArguments = primaryConstructor
                            .parameters
                            .filterNot { it.isOptional && settings.useDefaultValues }
                            .map {
                                it to createValue(it)
                            }
                            .toMap()
                    primaryConstructor.isAccessible = true
                    primaryConstructor.callBy(constructorArguments)
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Could not generate random value for class [${targetClass.qualifiedName}]", e)
        }
    }

    private fun createValue(it: KParameter): Any? {
        return when {
            specificValues.containsKey(it) -> specificValues[it]
            else                           -> it.type.randomValue()
        }
    }

    private fun validateConstructor() {
        requireNotNull(targetClass.primaryConstructor) {
            """
            Target class [${targetClass.qualifiedName}] has no primary constructor. This is probably a Java class. Call 'registerGenerator' to supply an instance generator.
            """
        }
    }

    private fun KClassifier?.isSealedClass() = this is KClass<*> && (this as KClass<*>).isSealed
    private fun KClass<*>.getRandomSubclass(): KClass<out Any> {
        return when {
            this.isAbstract && this.constructors.isEmpty() -> classGraphScan.getClassesImplementing(this.qualifiedName)
            else                                           -> classGraphScan.getSubclasses(this.qualifiedName)
        }.random().loadClass().kotlin
    }

    private fun KType.randomValue(): Any? {
        val nonNullableType = withNullability(false)
        return when {
            settings.generateNulls && isMarkedNullable  -> null
            canGenerate(nonNullableType)                -> generate(withNullability(false))
            isSubtypeOf(wildcardCollectionType)         -> fillCollection(this)
            isSubtypeOf(wildcardNullableCollectionType) -> fillCollection(this)
            isSubtypeOf(wildcardMapType)                -> fillMap(this)
            isSubtypeOf(wildcardNullableMapType)        -> fillMap(this)
            isSubtypeOf(wildcardEnumType)               -> RandomEnum.randomEnumValue(
                    (classifier as KClass<Enum<*>>).java)
            isSubtypeOf(wildcardNullableEnumType)       -> RandomEnum.randomEnumValue(
                    (classifier as KClass<Enum<*>>).java)

            classifier.isSealedClass()                  -> (this.classifier as KClass<*>).getRandomSubclass()
                    .createInstanceWithGenerator()
            classifier is KClass<*> && (classifier as KClass<*>).qualifiedName == "kotlin.Pair"
                                                        -> arguments[0].type!!.randomValue() to arguments[1].type!!.randomValue()
            classifier is KClass<*>                     -> (classifier as KClass<*>).createInstanceWithGenerator()
            else                                        -> {
                TODO("No support for ${this}")
            }
        }
    }

    fun KClass<*>.createInstanceWithGenerator() =
            InstanceCreator(this).apply { this.addAllIfMissing(this@InstanceCreator.generators) }
                    .createInstance()

    private fun fillMap(mapType: KType): Any {
        val keyType = mapType.arguments[0].type!!
        val valueType = mapType.arguments[1].type!!

        return (1..10)
                .map { keyType.randomValue() to valueType.randomValue() }
                .toMap()
    }

    private fun fillCollection(collectionType: KType): Any {
        val valueType = collectionType.arguments[0].type!!
        val randomValues = (1..2).map { valueType.randomValue() }

        return when {
            collectionType.isSubtypeOf(wildcardListType)               -> randomValues
            collectionType.isSubtypeOf(wildcardNullableListType)       -> randomValues
            collectionType.isSubtypeOf(wildcardSetType)                -> randomValues.toSet()
            collectionType.isSubtypeOf(wildcardNullableSetType)        -> randomValues.toSet()
            collectionType.isSubtypeOf(wildcardCollectionType)         -> randomValues
            collectionType.isSubtypeOf(wildcardNullableCollectionType) -> randomValues
            else                                                       -> TODO("No support for $collectionType")
        }
    }

    private fun generate(type: KType): Any = generators[type]!!.invoke()

    private fun canGenerate(type: KType) = generators.containsKey(type)
}

data class GeneratorSettings(
        val useDefaultValues: Boolean = true,
        val generateNulls: Boolean = false
                            )

