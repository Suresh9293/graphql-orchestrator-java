package com.intuit.graphql.orchestrator.resolverdirective;

import static com.intuit.graphql.orchestrator.utils.XtextUtils.getChildFields;
import static graphql.language.AstValueHelper.astFromValue;

import com.intuit.graphql.graphQL.Directive;
import com.intuit.graphql.graphQL.FieldDefinition;
import com.intuit.graphql.graphQL.InterfaceTypeDefinition;
import com.intuit.graphql.graphQL.ObjectTypeDefinition;
import com.intuit.graphql.graphQL.ObjectTypeExtensionDefinition;
import com.intuit.graphql.graphQL.TypeDefinition;
import com.intuit.graphql.orchestrator.schema.transform.FieldWithResolverMetadata;
import com.intuit.graphql.orchestrator.xtext.FieldContext;
import com.intuit.graphql.orchestrator.xtext.XtextGraph;
import com.intuit.graphql.utils.XtextTypeUtils;
import graphql.language.Argument;
import graphql.language.Value;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.emf.ecore.EObject;

public class FieldResolverDirectiveUtil {

  public static final String RESOLVER_DIRECTIVE_NAME = "resolver";
  public static final String FIELD_REFERENCE_PREFIX = "$";

  public static final CharSequence OPERATION_NAME_SEPARATOR = "_";
  public static final CharSequence RESOLVER_DIRECTIVE_QUERY_NAME = "Resolver_Directive_Query";

  private FieldResolverDirectiveUtil() {
  }

  public static List<Argument> createResolverQueryFieldArguments(List<ResolverArgument> resolverArguments,
      GraphQLFieldsContainer parentType, Map<String, Object> parentSource,
      ResolverDirectiveDefinition resolverDirectiveDefinition, String serviceNameSpace) {

    Objects.requireNonNull(resolverArguments);
    Objects.requireNonNull(parentSource);
    Objects.requireNonNull(resolverDirectiveDefinition);
    Objects.requireNonNull(serviceNameSpace);

    List<Argument> arguments = new ArrayList<>();
    resolverArguments.forEach(
        resolverArg -> {
          String resolverArgValue = resolverArg.getValue();
          if (isReferenceToFieldInParentType(resolverArgValue, parentType)) {
            String fieldReferenceName = getNameFromFieldReference(resolverArgValue);

            ifInvalidFieldReferenceThrowException(fieldReferenceName, parentType.getName(),
                resolverDirectiveDefinition, serviceNameSpace, parentSource);

            Object valueFromSource = parentSource.get(fieldReferenceName);
            GraphQLType fieldReferenceType = parentType.getFieldDefinition(fieldReferenceName).getType();
            Value<?> fieldRefSourceValue = astFromValue(valueFromSource, fieldReferenceType);
            Argument argument = Argument.newArgument(resolverArg.getName(), fieldRefSourceValue).build();
            arguments.add(argument);
          } else {
            throw new ResolverArgumentNotAFieldOfParentException(resolverArgValue, parentType.getName());
          }
        });
    return arguments;
  }

  private static void ifInvalidFieldReferenceThrowException(String fieldReferenceName, String parentTypeName,
      ResolverDirectiveDefinition resolverDirectiveDefinition, String serviceNameSpace,
      Map<String, Object> parentSource) {

    if (!parentSource.containsKey(fieldReferenceName)) {
      throw FieldNotFoundInParentException.builder()
          .fieldName(fieldReferenceName)
          .parentTypeName(parentTypeName)
          .resolverDirectiveDefinition(resolverDirectiveDefinition)
          .serviceNameSpace(serviceNameSpace)
          .build();
    }

    Object valueFromSource = parentSource.get(fieldReferenceName);
    if (Objects.isNull(valueFromSource)) {
      throw FieldValueIsNullInParentException.builder()
          .fieldName(fieldReferenceName)
          .parentTypeName(parentTypeName)
          .resolverDirectiveDefinition(resolverDirectiveDefinition)
          .serviceNameSpace(serviceNameSpace)
          .build();
    }

  }

  private static boolean isFieldReference(String string) {
    return StringUtils.startsWith(string, FIELD_REFERENCE_PREFIX)
        && StringUtils.length(string) > FIELD_REFERENCE_PREFIX.length();
  }

  private static String getNameFromFieldReference(String fieldReference) {
    return StringUtils.substring(fieldReference, FIELD_REFERENCE_PREFIX.length());
  }

  private static boolean isReferenceToFieldInParentType(String resolverArgValue, GraphQLFieldsContainer parentType) {
    if (isFieldReference(resolverArgValue)) {
      String fieldName = getNameFromFieldReference(resolverArgValue);
      return parentType.getFieldDefinition(fieldName) != null;
    } else {
      throw new NotAValidFieldReference(resolverArgValue);
    }
  }

  public static boolean isReferenceToFieldInParentType(String resolverArgValue, TypeDefinition parentType) {
    if (isFieldReference(resolverArgValue)) {
      String fieldName = getNameFromFieldReference(resolverArgValue);
      List<FieldDefinition> childFields = getChildFields(parentType);
      return childFields.stream().anyMatch(fieldDefinition -> StringUtils.equals(fieldDefinition.getName(), fieldName));
    } else {
      throw new NotAValidFieldReference(resolverArgValue);
    }
  }

  public static boolean canContainFieldResolverDirective(EObject eContainer) {
    // Do not support InterfaceType Extension as valid location as it may affect concrete types implementing the interface
    return eContainer instanceof ObjectTypeExtensionDefinition || eContainer instanceof ObjectTypeDefinition;
  }

  public static String getResolverDirectiveParentTypeName(Directive directive) {
    // see canContainFieldWithResolverDirective
    Objects.requireNonNull(directive, "directive is null");
    Objects.requireNonNull(directive.eContainer(), "directive container is null");
    EObject container = directive.eContainer();

    if (container instanceof FieldDefinition) {
      FieldDefinition fieldDefinition = (FieldDefinition) container;
      return XtextTypeUtils.typeName(fieldDefinition.getNamedType());
    }

    throw new UnexpectedResolverDirectiveParentType(String.format("Expecting parent to be an instance of "
        + "FieldDefinition.  directive=%s, pareTypeInstance=%s", directive.getDefinition().getName(),
        container.getClass().getSimpleName())
    );
  }

  public static boolean hasResolverDirective(GraphQLFieldDefinition fieldDefinition) {
    return fieldDefinition.getDirective(RESOLVER_DIRECTIVE_NAME) != null;
  }

  public static boolean isInputTypeFieldContainer(TypeDefinition typeDefinition) {
    return typeDefinition instanceof ObjectTypeDefinition || typeDefinition instanceof InterfaceTypeDefinition;
  }

  public static List<FieldWithResolverMetadata> createFieldWithResolverMetadatas(
      TypeDefinition typeDefinition, XtextGraph xtextGraph) {

    List<FieldDefinition> childFields = getChildFields(typeDefinition);
    return childFields.stream()
        .map(childFieldDefinition -> {
          List<Directive> directives = getResolverDirective(childFieldDefinition);

          if (CollectionUtils.size(directives) > 1) {
            throw new MultipleResolverDirectiveDefinition(CollectionUtils.size(directives));
          }

          if (CollectionUtils.size(directives) < 1) {
            return Optional.<FieldWithResolverMetadata>empty();
          }

          FieldWithResolverMetadata fieldWithResolverMetadata = FieldWithResolverMetadata.builder()
              .fieldContext(new FieldContext(typeDefinition.getName(), childFieldDefinition.getName()))
              .fieldDefinition(childFieldDefinition)
              .parentTypeDefinition(typeDefinition)
              .requiresTypeNameInjection(xtextGraph.requiresTypenameInjection())
              .serviceNamespace(xtextGraph.getServiceProvider().getNameSpace())
              .resolverDirectiveDefinition(ResolverDirectiveDefinition.from(directives.get(0)))
              .build();

          return Optional.of(fieldWithResolverMetadata);
        })
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(Collectors.toList());
  }

  private static List<Directive> getResolverDirective(FieldDefinition fieldDefinition) {
    return fieldDefinition.getDirectives().stream()
        .filter(directive -> StringUtils.equals(RESOLVER_DIRECTIVE_NAME, directive.getDefinition().getName()))
        .collect(Collectors.toList());
  }

  public static String createAlias(String fieldName, int counter) {
    return String.join("_", fieldName, Integer.toString(counter));
  }

  public static String createFieldResolverOperationName(String originalOperationName) {
    return String.join(OPERATION_NAME_SEPARATOR, originalOperationName, RESOLVER_DIRECTIVE_QUERY_NAME);
  }
}