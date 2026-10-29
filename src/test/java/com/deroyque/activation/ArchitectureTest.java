package com.deroyque.activation;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Regras de dependência entre camadas: o domínio não conhece ninguém, a aplicação não conhece HTTP
 * nem detalhes de infraestrutura, e a API só conversa com aplicação e domínio.
 */
@AnalyzeClasses(packages = "com.deroyque.activation", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	@ArchTest
	static final ArchRule layers = layeredArchitecture()
			.consideringOnlyDependenciesInLayers()
			.layer("API").definedBy("..activation.api..")
			.layer("Application").definedBy("..activation.application..")
			.layer("Domain").definedBy("..activation.domain..")
			.layer("Infrastructure").definedBy("..activation.infrastructure..")
			.whereLayer("API").mayNotBeAccessedByAnyLayer()
			.whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()
			.whereLayer("Application").mayOnlyBeAccessedByLayers("API", "Infrastructure")
			.whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "API", "Infrastructure");

	@ArchTest
	static final ArchRule domainAndApplicationDoNotDependOnWeb = noClasses()
			.that().resideInAnyPackage("..activation.domain..", "..activation.application..")
			.should().dependOnClassesThat()
			.resideInAnyPackage("org.springframework.web..", "org.springframework.http..", "jakarta.servlet..");

	@ArchTest
	static final ArchRule apiDoesNotAccessRepositoriesDirectly = noClasses()
			.that().resideInAPackage("..activation.api..")
			.should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");

}
