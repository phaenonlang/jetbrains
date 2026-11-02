package dev.phaenon.jetbrains

import com.jetbrains.php.lang.psi.elements.impl.PhpExpressionImpl
import com.jetbrains.php.lang.psi.resolve.types.PhpType

/**
 * PhpStorm's encoding of a closure signature, `\Closure<#ᤓᤓ\string,\bool>`: one part per
 * parameter, then the return type, a union's members joined in one part. Built with PhpStorm's own
 * serializer, as [PhaenonTypeText] builds it.
 */
fun closure(parameters: List<String>, vararg returns: String): String =
    PhpType.createParametrizedType(
        PhpType._CLOSURE,
        parameters.map { PhpExpressionImpl.serializeAdvancedClosureParameter("", it) } +
            returns.sorted().joinToString(PhpExpressionImpl.CALLABLE_PARAMS_TYPES_SEPARATOR),
    )
