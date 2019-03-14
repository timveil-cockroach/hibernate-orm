/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.sqm.tree.domain;

import java.util.Locale;
import java.util.function.Supplier;

import org.hibernate.NotYetImplementedFor6Exception;
import org.hibernate.annotations.Remove;
import org.hibernate.metamodel.model.domain.ManagedDomainType;
import org.hibernate.metamodel.model.domain.spi.EmbeddedTypeDescriptor;
import org.hibernate.metamodel.model.domain.spi.EmbeddedValuedNavigable;
import org.hibernate.metamodel.model.domain.spi.EntityTypeDescriptor;
import org.hibernate.metamodel.model.domain.spi.EntityValuedNavigable;
import org.hibernate.metamodel.model.domain.spi.Navigable;
import org.hibernate.metamodel.model.domain.spi.PersistentCollectionDescriptor;
import org.hibernate.metamodel.model.domain.spi.PluralValuedNavigable;
import org.hibernate.query.NavigablePath;
import org.hibernate.query.sqm.SemanticException;
import org.hibernate.query.sqm.produce.SqmCreationHelper;
import org.hibernate.query.sqm.produce.path.spi.SemanticPathPart;
import org.hibernate.query.sqm.produce.spi.SqmCreationState;
import org.hibernate.query.sqm.tree.expression.SqmExpression;
import org.hibernate.query.sqm.tree.expression.domain.SqmNavigableReference;
import org.hibernate.query.sqm.tree.expression.domain.SqmRestrictedCollectionElementReference;

/**
 * Models a reference to a part of the application's domain model (a Navigable)
 * as part of an SQM tree.
 *
 * This correlates roughly to the JPA Criteria notion of Path, hence the name.
 *
 * todo (6.0) : Better name for this.
 * 		* SqmNavigablePath?
 * 		* Maybe just re-purpose SqmNavigableReference for this purpose?
 * 		* SqmPathExpression?
 * 		* SqmDomainPath
 *
 * todo (6.0) : part of this might be renaming NavigablePath which is also not the best name
 *
 * @author Steve Ebersole
 */
public interface SqmPath extends SqmExpression, SemanticPathPart {
	/**
	 * @deprecated Prefer {@link #getNavigablePath()} as the unique identifier
	 */
	@Deprecated
	@Remove
	String getUniqueIdentifier();

	/**
	 * Returns the NavigablePath.
	 */
	NavigablePath getNavigablePath();

	/**
	 * The Navigable represented by this reference.
	 */
	Navigable<?> getReferencedNavigable();

	/**
	 * Get the left-hand side of this path - may be null, indicating a
	 * root, cross-join or entity-join
	 */
	SqmPath getLhs();

	/**
	 * Retrieve the explicit alias, if one.  May return null
	 */
	String getExplicitAlias();

	void setExplicitAlias(String explicitAlias);

	@Override
	default SqmRestrictedCollectionElementReference resolveIndexedAccess(
			SqmExpression selector,
			String currentContextKey,
			boolean isTerminal,
			SqmCreationState creationState) {
		if ( getReferencedNavigable() instanceof PluralValuedNavigable<?> ) {
			throw new NotYetImplementedFor6Exception();
		}

		throw new SemanticException( "Non-plural path [" + currentContextKey + "] cannot be index-accessed" );
	}

	/**
	 * Perform any preparations needed to process the named Navigable.  Create joins?
	 *
	 * This should equate to resolution of implicit joins.  Given
	 * `select p.address.city from Person p ....`, e.g.,  we'd end up with the following:
	 *
	 * 		1) 	Because we process the from-clause first, `Person p` is already available as an
	 * 		   	SqmRoot with NavigablePath[Person(p)]
	 * 		2) 	As we process the select-clause, the `p.address.city` dot-ident sequence is processed
	 * 		   	by the registered `DotIdentifierConsumer`
	 *
	 *			1)	the first part (`p`) is resolved, internally, as the registered SqmRoot as an alias
	 *				which is tracked there as its "current `SemanticPathPart`"
	 *			2)	each "continuation"	(here `address` and then `city`) is handled by applying that
	 *				name to the "current `SemanticPathPart`", assigning its result back as the new
	 *				"current `SemanticPathPart`"
	 *
	 *					1) `address` is resolved against SqmRoot, producing a `SqmEmbeddedValuedSimplePath`
	 *						with NavigablePath[Person(p).address].  That is registered in the PathRegistry
	 *						in `#sqmPathMap`, but not (yet) in `#sqmFromPath`.
	 *					2)	`city` is resolved against the SqmEmbeddedValuedSimplePath(NavigablePath[Person(p).address]).
	 *						This triggers a few things:
	 *
	 *						1) 	SqmEmbeddedValuedSimplePath( Person(p).address ) is given a
	 *							chance to prepare itself to be used as the LHS via this `#prepareForSubNavigableReference`
	 *							method.  Here, we use that opportunity to create the implicit SqmNavigableJoin for
	 *							the same `Person(p).address` path.  We register this join form with the PathRegistry
	 *							which "over-writes" the previous `SqmEmbeddedValuedSimplePath` registration
	 *						2)	Processing `city` produces a `SqmBasicValuedSimplePath( Person(p).address.city )`
	 *							which is registered in the PathRegistry, again just in `#sqmPathMap`, but not (yet)
	 *							in `#sqmFromPath`
	 *
	 * 		At this point processing would return from `DotIdentifierConsumer` back to `SemanticQueryBuilder`
	 * 		where we call `DotIdentifierConsumer#getConsumedPart` to get the last "current part"
	 * 		`SqmBasicValuedSimplePath( Person(p).address.city )` as the result for the fully resolved
	 * 		dot-ident-sequence
	 *
	 * 	todo (6.0) : ideally we'd delay this until SQM -> SQL AST conversion : criteria-as-SQM
	 */
	default void prepareForSubNavigableReference(
			SqmPath subReference,
			boolean isSubReferenceTerminal,
			SqmCreationState creationState) {
		SqmCreationHelper.resolveAsLhs( getLhs(), this, subReference, isSubReferenceTerminal, creationState );
	}

	/**
	 * Treat this path as the given type.  "Cast it" to the target type.
	 *
	 * May throw an exception if the Path is not treatable as the requested type.
	 *
	 * Also recognizes any {@link Navigable} target type and applies it to the
	 * {@link #getReferencedNavigable()}.
	 *
	 * @return The "casted" reference
	 */
	@SuppressWarnings("unchecked")
	default <T> T as(Class<T> targetType) {
		if ( targetType.isInstance( this ) ) {
			return (T) this;
		}

		if ( Navigable.class.isAssignableFrom( targetType ) ) {
			return (T) ( (Navigable) getReferencedNavigable() ).as( targetType );
		}

		throw new IllegalArgumentException(
				String.format(
						Locale.ROOT,
						"`%s` cannot be treated as `%s`",
						getClass().getName(),
						targetType.getName()
				)
		);
	}

	default <T> T as(Class<T> targetType, Supplier<RuntimeException> exceptionSupplier) {
		try {
			return as( targetType );
		}
		catch (IllegalArgumentException e) {
			throw exceptionSupplier.get();
		}
	}
}