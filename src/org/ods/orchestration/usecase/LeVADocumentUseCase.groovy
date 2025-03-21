package org.ods.orchestration.usecase

import com.cloudbees.groovy.cps.NonCPS

import java.time.LocalDateTime
import org.ods.services.GitService
import org.ods.services.JenkinsService
import org.ods.services.NexusService
import org.ods.services.OpenShiftService
import org.ods.orchestration.scheduler.LeVADocumentScheduler
import org.ods.orchestration.service.*
import org.ods.orchestration.util.*
import org.ods.util.IPipelineSteps
import org.ods.util.Logger

import groovy.xml.XmlUtil

@SuppressWarnings(['IfStatementBraces',
    'LineLength',
    'AbcMetric',
    'Instanceof',
    'VariableName',
    'UnusedMethodParameter',
    'UnusedVariable',
    'ParameterCount',
    'NonFinalPublicField',
    'PropertyName',
    'MethodCount',
    'UseCollectMany',
    'ParameterName',
    'TrailingComma',
    'SpaceAroundMapEntryColon'])
class LeVADocumentUseCase extends DocGenUseCase {

    enum DocumentType {

        CSD,
        DIL,
        DTP,
        DTR,
        RA,
        CFTP,
        CFTR,
        IVP,
        IVR,
        SSDS,
        TCP,
        TCR,
        TIP,
        TIR,
        TRC,
        OVERALL_DTR,
        OVERALL_IVR,
        OVERALL_TIR

    }

    protected static Map DOCUMENT_TYPE_NAMES = [
        (DocumentType.CSD as String)        : 'Combined Specification Document',
        (DocumentType.DIL as String)        : 'Discrepancy Log',
        (DocumentType.DTP as String)        : 'Software Development Testing Plan',
        (DocumentType.DTR as String)        : 'Software Development Testing Report',
        (DocumentType.CFTP as String)       : 'Combined Functional and Requirements Testing Plan',
        (DocumentType.CFTR as String)       : 'Combined Functional and Requirements Testing Report',
        (DocumentType.IVP as String)        : 'Configuration and Installation Testing Plan',
        (DocumentType.IVR as String)        : 'Configuration and Installation Testing Report',
        (DocumentType.RA as String)         : 'Risk Assessment',
        (DocumentType.TRC as String)        : 'Traceability Matrix',
        (DocumentType.SSDS as String)       : 'System and Software Design Specification',
        (DocumentType.TCP as String)        : 'Test Case Plan',
        (DocumentType.TCR as String)        : 'Test Case Report',
        (DocumentType.TIP as String)        : 'Technical Installation Plan',
        (DocumentType.TIR as String)        : 'Technical Installation Report',
        (DocumentType.OVERALL_DTR as String): 'Overall Software Development Testing Report',
        (DocumentType.OVERALL_IVR as String): 'Overall Configuration and Installation Testing Report',
        (DocumentType.OVERALL_TIR as String): 'Overall Technical Installation Report',
    ]

    static GAMP_CATEGORY_SENSITIVE_DOCS = [
        DocumentType.SSDS as String,
        DocumentType.CSD as String,
        DocumentType.CFTP as String,
        DocumentType.CFTR as String
    ]

    static Map<String, Map> DOCUMENT_TYPE_FILESTORAGE_EXCEPTIONS = [
        'SCRR-MD' : [storage: 'pdf', content: 'pdf' ]
    ]

    public static String DEVELOPER_PREVIEW_WATERMARK = 'Developer Preview'
    public static String WORK_IN_PROGRESS_WATERMARK = 'Work in Progress'
    public static String WORK_IN_PROGRESS_DOCUMENT_MESSAGE = 'Attention: this document is work in progress!'

    private JiraUseCase jiraUseCase
    private JUnitTestReportsUseCase junit
    private LeVADocumentChaptersFileService levaFiles
    private OpenShiftService os
    private SonarQubeUseCase sq

    LeVADocumentUseCase(Project project, IPipelineSteps steps, MROPipelineUtil util, DocGenService docGen, JenkinsService jenkins, JiraUseCase jiraUseCase, JUnitTestReportsUseCase junit, LeVADocumentChaptersFileService levaFiles, NexusService nexus, OpenShiftService os, PDFUtil pdf, SonarQubeUseCase sq) {
        super(project, steps, util, docGen, nexus, pdf, jenkins)
        this.jiraUseCase = jiraUseCase
        this.junit = junit
        this.levaFiles = levaFiles
        this.os = os
        this.sq = sq
    }

    @SuppressWarnings('CyclomaticComplexity')
    String createCSD(Map repo = null, Map data = null) {
        def documentType = DocumentType.CSD as String

        def sections = this.getDocumentSections(documentType)
        def watermarkText = this.getWatermarkText(documentType, this.project.hasWipJiraIssues())

        def requirements = this.project.getSystemRequirements()

        def reqsWithNoGampTopic = requirements.findAll { it.gampTopic == null }
        def reqsGroupedByGampTopic = requirements. findAll { it.gampTopic != null }
            .groupBy { it.gampTopic.toLowerCase() }
        reqsGroupedByGampTopic << ['uncategorized': reqsWithNoGampTopic ]

        def requirementsForDocument = reqsGroupedByGampTopic.collectEntries { gampTopic, reqs ->
            def updatedReqs = reqs.collect { req ->
                def epics = req.getResolvedEpics()
                def epic = !epics.isEmpty() ? epics.first() : null
                [
                    key             : req.key,
                    applicability   : 'Mandatory',
                    ursName         : req.name,
                    ursDescription  : this.convertImages(req.description ?: ''),
                    csName          : req.configSpec.name ?: 'N/A',
                    csDescription   : this.convertImages(req.configSpec.description ?: ''),
                    fsName          : req.funcSpec.name ?: 'N/A',
                    fsDescription   : this.convertImages(req.funcSpec.description ?: ''),
                    epic            : epic?.key,
                    epicName        : epic?.epicName,
                    epicTitle       : epic?.title,
                    epicDescription : epic?.description,
                ]
            }

            def reqsGroupByEpic = SortUtil.sortIssuesByKey(updatedReqs).findAll{
                it.epic != null}.groupBy{it.epic}

            def index = 0
            def reqsGroupByEpicUpdated = reqsGroupByEpic.collect { req ->
                index = index + 1
                [
                        epicName        : req.value.epicName.first(),
                        epicTitle       : req.value.epicTitle.first(),
                        epicDescription : this.convertImages(req.value.epicDescription.first() ?: ''),
                        key             : req.key,
                        epicIndex       : index,
                        stories         : req.value,
                ]
            }
            def output = [
                noepics: SortUtil.sortIssuesByKey(updatedReqs).findAll{ it.epic == null },
                epics  : SortUtil.sortIssuesByKey(reqsGroupByEpicUpdated)
            ]

            [
                (gampTopic.replaceAll(' ', '').toLowerCase()): output
            ]
        }

        def keysInDoc = this.project.getRequirements()
            .collect { it.subMap(['key', 'epics']).values()  }
            .flatten().unique()

        if(project.data?.jira?.discontinuationsPerType) {
            keysInDoc += project.data.jira.discontinuationsPerType.requirements*.key
            keysInDoc += project.data.jira.discontinuationsPerType.epics*.key
        }

        def docHistory = this.getAndStoreDocumentHistory(documentType, keysInDoc)
        def data_ = [
            metadata: this.getDocumentMetadata(this.DOCUMENT_TYPE_NAMES[documentType]),
            data    : [
                sections    : sections,
                requirements: requirementsForDocument,
                documentHistory: docHistory?.getDocGenFormat() ?: []
            ]
        ]

        def uri = this.createDocument(documentType, null, data_, [:], null, getDocumentTemplateName(documentType), watermarkText)
        this.updateJiraDocumentationTrackingIssue(documentType, uri, docHistory?.getVersion() as String)
        return uri
    }

    String createDTP(Map repo = null, Map data = null) {
        def documentType = DocumentType.DTP as String

        def sections = this.getDocumentSectionsFileOptional(documentType)
        def watermarkText = this.getWatermarkText(documentType, this.project.hasWipJiraIssues())

        def unitTests = this.project.getAutomatedTestsTypeUnit()
        def tests = this.computeTestsWithRequirementsAndSpecs(unitTests)
        def modules = this.getReposWithUnitTestsInfo(unitTests)

        def keysInDoc = modules.collect { 'Technology-' + it.id } + tests
            .collect {[it.testKey, it.systemRequirement.split(', '), it.softwareDesignSpec.split(', ')]  }.flatten()

        def docHistory = this.getAndStoreDocumentHistory(documentType, keysInDoc)

        def data_ = [
            metadata: this.getDocumentMetadata(this.DOCUMENT_TYPE_NAMES[documentType], repo),
            data    : [
                sections: sections,
                tests: tests,
                modules: modules,
                documentHistory: docHistory?.getDocGenFormat() ?: [],
            ]
        ]

        def uri = this.createDocument(documentType, null, data_, [:], null, getDocumentTemplateName(documentType), watermarkText)
        this.updateJiraDocumentationTrackingIssue(documentType, uri, docHistory?.getVersion() as String)
        return uri
    }

    String createDTR(Map repo, Map data) {
        def documentType = DocumentType.DTR as String

        Map resurrectedDocument = resurrectAndStashDocument(documentType, repo)
        this.steps.echo "Resurrected ${documentType} for ${repo.id} -> (${resurrectedDocument.found})"
        if (resurrectedDocument.found) {
            return resurrectedDocument.uri
        }

        def unitTestData = data.tests.unit

        def sections = this.getDocumentSectionsFileOptional(documentType)
        def watermarkText = this.getWatermarkText(documentType, this.project.hasWipJiraIssues())

        def testIssues = this.project.getAutomatedTestsTypeUnit("Technology-${repo.id}")
        def discrepancies = this.computeTestDiscrepancies("Development Tests", testIssues, unitTestData.testResults)

        def obtainEnum = { category, value ->
            return this.project.getEnumDictionary(category)[value as String]
        }

        def tests = testIssues.collect { testIssue ->
            def description = testIssue.name ?: ""
            if (description && testIssue.description) {
                description += ": "
            }
            description += testIssue.description

            def riskLevels = testIssue.getResolvedRisks(). collect {
                def value = obtainEnum("SeverityOfImpact", it.severityOfImpact)
                return value ? value.text : "None"
            }

            def softwareDesignSpecs = testIssue.getResolvedTechnicalSpecifications()
                .findAll { it.softwareDesignSpec }
                .collect { it.key }

            [
                key               : testIssue.key,
                description       : description ?: "N/A",
                systemRequirement : testIssue.requirements.join(", "),
                success           : testIssue.isSuccess ? "Y" : "N",
                remarks           : testIssue.isUnexecuted ? "Not executed" : "N/A",
                softwareDesignSpec: (softwareDesignSpecs.join(", ")) ?: "N/A",
                riskLevel         : riskLevels ? riskLevels.join(", ") : "N/A"
            ]
        }

        def keysInDoc = tests.collect {
            [it.key, it.systemRequirement.split(', '), it.softwareDesignSpec.split(', ')]
        }.flatten()
        def docHistory = this.getAndStoreDocumentHistory(documentType + '-' + repo.id, keysInDoc)

        def data_ = [
            metadata: this.getDocumentMetadata(this.DOCUMENT_TYPE_NAMES[documentType], repo),
            data    : [
                repo              : repo,
                sections          : sections,
                tests             : tests,
                numAdditionalTests: junit.getNumberOfTestCases(unitTestData.testResults) - testIssues.count { !it.isUnexecuted },
                testFiles         : SortUtil.sortIssuesByProperties(unitTestData.testReportFiles.collect { file ->
                    [name: file.name, path: file.path, text: XmlUtil.serialize(file.text)]
                } ?: [], ["name"]),
                discrepancies     : discrepancies.discrepancies,
                conclusion        : [
                    summary  : discrepancies.conclusion.summary,
                    statement: discrepancies.conclusion.statement
                ],
                documentHistory: docHistory?.getDocGenFormat() ?: [],
            ]
        ]

        def files = unitTestData.testReportFiles.collectEntries { file ->
            ["raw/${file.getName()}", file.getBytes()]
        }

        def modifier = { document ->
            return document
        }

        return this.createDocument(documentType, repo, data_, files, modifier, getDocumentTemplateName(documentType, repo), watermarkText)
    }

    String createOverallDTR(Map repo = null, Map data = null) {
        def documentTypeName = DOCUMENT_TYPE_NAMES[DocumentType.OVERALL_DTR as String]
        def metadata = this.getDocumentMetadata(documentTypeName)
        def documentType = DocumentType.DTR as String

        def watermarkText = this.getWatermarkText(documentType, this.project.hasWipJiraIssues())

        def uri = this.createOverallDocument('Overall-Cover', documentType, metadata, null, watermarkText)
        def docHistory = this.project.findHistoryForDocumentType(documentType)
        this.updateJiraDocumentationTrackingIssue(documentType, uri, docHistory?.getVersion() as String)
        return uri
    }

    String createDIL(Map repo = null, Map data = null) {
        def documentType = DocumentType.DIL as String

        def watermarkText = this.getWatermarkText(documentType, this.project.hasWipJiraIssues())

        def bugs = this.project.getBugs().each { bug ->
            bug.tests = bug.getResolvedTests()
        }

        def acceptanceTestBugs = bugs.findAll { bug ->
            bug.tests.findAll { test ->
                test.testType == Project.TestType.ACCEPTANCE
            }
        }

        def integrationTestBugs = bugs.findAll { bug ->
            bug.tests.findAll { test ->
                test.testType == Project.TestType.INTEGRATION
            }
        }

        SortUtil.sortIssuesByKey(acceptanceTestBugs)
        SortUtil.sortIssuesByKey(integrationTestBugs)

        def metadata = this.getDocumentMetadata(this.DOCUMENT_TYPE_NAMES[documentType])
        metadata.orientation = "Landscape"

        def data_ = [
            metadata: metadata,
            data    : [:]
        ]

        if (!integrationTestBugs.isEmpty()) {
            data_.data.integrationTests = integrationTestBugs.collect { bug ->
                [
                    //Discrepancy ID -> BUG Issue ID
                    discrepancyID        : bug.key,
                    //Test Case No. -> JIRA (Test Case Key)
                    testcaseID           : bug.tests. collect { it.key }.join(", "),
                    //- Level of Test Case = Unit / Integration / Acceptance / Installation
                    level                : "Integration",
                    //Description of Failure or Discrepancy -> Bug Issue Summary
                    description          : bug.name,
                    //Remediation Action -> "To be fixed"
                    remediation          : "To be fixed",
                    //Responsible / Due Date -> JIRA (assignee, Due date)
                    responsibleAndDueDate: "${bug.assignee ? bug.assignee : 'N/A'} / ${bug.dueDate ? bug.dueDate : 'N/A'}",
                    //Outcome of the Resolution -> Bug Status
                    outcomeResolution    : bug.status,
                    //Resolved Y/N -> JIRA Status -> Done = Yes
                    resolved             : bug.status == "Done" ? "Yes" : "No"
                ]
            }
        }

        if (!acceptanceTestBugs.isEmpty()) {
            data_.data.acceptanceTests = acceptanceTestBugs.collect { bug ->
                [
                    //Discrepancy ID -> BUG Issue ID
                    discrepancyID        : bug.key,
                    //Test Case No. -> JIRA (Test Case Key)
                    testcaseID           : bug.tests. collect { it.key }.join(", "),
                    //- Level of Test Case = Unit / Integration / Acceptance / Installation
                    level                : "Acceptance",
                    //Description of Failure or Discrepancy -> Bug Issue Summary
                    description          : bug.name,
                    //Remediation Action -> "To be fixed"
                    remediation          : "To be fixed",
                    //Responsible / Due Date -> JIRA (assignee, Due date)
                    responsibleAndDueDate: "${bug.assignee ? bug.assignee : 'N/A'} / ${bug.dueDate ? bug.dueDate : 'N/A'}",
                    //Outcome of the Resolution -> Bug Status
                    outcomeResolution    : bug.status,
                    //Resolved Y/N -> JIRA Status -> Done = Yes
                    resolved             : bug.status == "Done" ? "Yes" : "No"
                ]
            }
        }

        def uri = this.createDocument(documentType, null, data_, [:], null, getDocumentTemplateName(documentType), watermarkText)
        this.updateJiraDocumentationTrackingIssue(documentType, uri)
        return uri
    }

    String createCFTP(Map repo = null, Map data = null) {
        def documentType = DocumentType.CFTP as String

        def sections = this.getDocumentSections(documentType)
        def watermarkText = this.getWatermarkText(documentType, this.project.hasWipJiraIssues())

        def acceptanceTestIssues = this.project.getAutomatedTestsTypeAcceptance()
        def integrationTestIssues = this.project.getAutomatedTestsTypeIntegration()

        def keysInDoc = (integrationTestIssues + acceptanceTestIssues)
            .collect { it.subMap(['key']).values() }.flatten()

        def docHistory = this.getAndStoreDocumentHistory(documentType, keysInDoc)

        def data_ = [
            metadata: this.getDocumentMetadata(this.DOCUMENT_TYPE_NAMES[documentType]),
            data    : [
                sections        : sections,
                acceptanceTests : acceptanceTestIssues.collect { testIssue ->
                    [
                        key        : testIssue.key,
                        description: testIssue.description ?: '',
                        ur_key     : testIssue.requirements ? testIssue.requirements.join(', ') : 'N/A',
                        risk_key   : testIssue.risks ? testIssue.risks.join(', ') : 'N/A'
                    ]
                },
                integrationTests: integrationTestIssues.collect { testIssue ->
                    [
                        key        : testIssue.key,
                        description: testIssue.description ?: '',
                        ur_key     : testIssue.requirements ? testIssue.requirements.join(', ') : 'N/A',
                        risk_key   : testIssue.risks ? testIssue.risks.join(', ') : 'N/A'
                    ]
                },
                documentHistory: docHistory?.getDocGenFormat() ?: [],
            ]
        ]

        def uri = this.createDocument(documentType, null, data_, [:], null, getDocumentTemplateName(documentType), watermarkText)
        this.updateJiraDocumentationTrackingIssue(documentType, uri, docHistory?.getVersion() as String)
        return uri
    }

    @SuppressWarnings('CyclomaticComplexity')
    String createCFTR(Map repo, Map data) {
        def documentType = DocumentType.CFTR as String

        def acceptanceTestData = data.tests.acceptance
        def integrationTestData = data.tests.integration

        def sections = this.getDocumentSections(documentType)
        def watermarkText = this.getWatermarkText(documentType, this.project.hasWipJiraIssues())

        def acceptanceTestIssues = SortUtil.sortIssuesByKey(this.project.getAutomatedTestsTypeAcceptance())
        def integrationTestIssues = SortUtil.sortIssuesByKey(this.project.getAutomatedTestsTypeIntegration())
        def discrepancies = this.computeTestDiscrepancies("Integration and Acceptance Tests", (acceptanceTestIssues + integrationTestIssues), junit.combineTestResults([acceptanceTestData.testResults, integrationTestData.testResults]))

        def keysInDoc = (integrationTestIssues + acceptanceTestIssues)
            .collect { it.subMap(['key']).values() }.flatten()

        def docHistory = this.getAndStoreDocumentHistory(documentType, keysInDoc)

        def data_ = [
            metadata: this.getDocumentMetadata(this.DOCUMENT_TYPE_NAMES[documentType]),
            data    : [
                sections                     : sections,
                numAdditionalAcceptanceTests : junit.getNumberOfTestCases(acceptanceTestData.testResults) - acceptanceTestIssues.count { !it.isUnexecuted },
                numAdditionalIntegrationTests: junit.getNumberOfTestCases(integrationTestData.testResults) - integrationTestIssues.count { !it.isUnexecuted },
                conclusion                   : [
                    summary  : discrepancies.conclusion.summary,
                    statement: discrepancies.conclusion.statement
                ],
                documentHistory: docHistory?.getDocGenFormat() ?: [],
            ]
        ]

        if (!acceptanceTestIssues.isEmpty()) {
            data_.data.acceptanceTests = acceptanceTestIssues.collect { testIssue ->
                def description = testIssue.name ?: ""
                if (description && testIssue.description) {
                    description += ": "
                }
                description += testIssue.description

                [
                    key        : testIssue.key,
                    datetime   : testIssue.timestamp ? testIssue.timestamp.replaceAll("T", "</br>") : "N/A",
                    description: description ?: "N/A",
                    remarks    : testIssue.isUnexecuted ? "Not executed" : "",
                    risk_key   : testIssue.risks ? testIssue.risks.join(", ") : "N/A",
                    success    : testIssue.isSuccess ? "Y" : "N",
                    ur_key     : testIssue.requirements ? testIssue.requirements.join(", ") : "N/A"
                ]
            }
        }

        if (!integrationTestIssues.isEmpty()) {
            data_.data.integrationTests = integrationTestIssues.collect { testIssue ->
                def description = testIssue.name ?: ""
                if (description && testIssue.description) {
                    description += ": "
                }
                description += testIssue.description

                [
                    key        : testIssue.key,
                    datetime   : testIssue.timestamp ? testIssue.timestamp.replaceAll("T", "</br>") : "N/A",
                    description: description ?: "N/A",
                    remarks    : testIssue.isUnexecuted ? "Not executed" : "",
                    risk_key   : testIssue.risks ? testIssue.risks.join(", ") : "N/A",
                    success    : testIssue.isSuccess ? "Y" : "N",
                    ur_key     : testIssue.requirements ? testIssue.requirements.join(", ") : "N/A"
                ]
            }
        }

        def files = (acceptanceTestData.testReportFiles + integrationTestData.testReportFiles).collectEntries { file ->
            ["raw/${file.getName()}", file.getBytes()]
        }

        def uri = this.createDocument(documentType, null, data_, files, null, getDocumentTemplateName(documentType), watermarkText)
        this.updateJiraDocumentationTrackingIssue(documentType, uri, docHistory?.getVersion() as String)
        return uri
    }

    String createRA(Map repo = null, Map data = null) {
        def documentType = DocumentType.RA as String

        def sections = this.getDocumentSections(documentType)
        def watermarkText = this.getWatermarkText(documentType, this.project.hasWipJiraIssues())

        def obtainEnum = { category, value ->
            return this.project.getEnumDictionary(category)[value as String]
        }

        def risks = this.project.getRisks().collect { r ->
            def mitigationsText = r.mitigations ? r.mitigations.join(", ") : "None"
            def testsText = r.tests ? r.tests.join(", ") : "None"
            def requirements = (r.getResolvedSystemRequirements() + r.getResolvedTechnicalSpecifications())
            def gxpRelevance = obtainEnum("GxPRelevance", r.gxpRelevance)
            def probabilityOfOccurrence = obtainEnum("ProbabilityOfOccurrence", r.probabilityOfOccurrence)
            def severityOfImpact = obtainEnum("SeverityOfImpact", r.severityOfImpact)
            def probabilityOfDetection = obtainEnum("ProbabilityOfDetection", r.probabilityOfDetection)
            def riskPriority = obtainEnum("RiskPriority", r.riskPriority)

            return [
                key: r.key,
                name: r.name,
                description: r.description,
                proposedMeasures: "Mitigations: ${mitigationsText}<br/>Tests: ${testsText}",
                requirements: requirements.collect { it.name }.join("<br/>"),
                requirementsKey: requirements.collect { it.key }.join("<br/>"),
                gxpRelevance: gxpRelevance ? gxpRelevance."short" : "None",
                probabilityOfOccurrence: probabilityOfOccurrence ? probabilityOfOccurrence."short" : "None",
                severityOfImpact: severityOfImpact ? severityOfImpact."short" : "None",
                probabilityOfDetection: probabilityOfDetection ? probabilityOfDetection."short" : "None",
                riskPriority: riskPriority ? riskPriority.value : "N/A",
                riskPriorityNumber: r.riskPriorityNumber ?: "N/A",
                riskComment: r.riskComment ? r.riskComment : "N/A",
            ]
        }

        def proposedMeasuresDesription = this.project.getRisks().collect { r ->
            (r.getResolvedTests().collect {
                if (!it) throw new IllegalArgumentException("Error: test for requirement ${r.key} could not be obtained. Check if all of ${r.tests.join(", ")} exist in JIRA")
                [key: it.key, name: it.name, description: it.description, type: "test", referencesRisk: r.key]
            } + r.getResolvedMitigations().collect { [key: it.key, name: it.name, description: it.description, type: "mitigation", referencesRisk: r.key] })
        }.flatten()

        if (!sections."sec4s2s2") sections."sec4s2s2" = [:]

        if (this.project.getProjectProperties()."PROJECT.USES_POO" == "true") {
            sections."sec4s2s2" = [
                usesPoo          : "true",
                lowDescription   : this.project.getProjectProperties()."PROJECT.POO_CAT.LOW",
                mediumDescription: this.project.getProjectProperties()."PROJECT.POO_CAT.MEDIUM",
                highDescription  : this.project.getProjectProperties()."PROJECT.POO_CAT.HIGH"
            ]
        }

        if (!sections."sec5") sections."sec5" = [:]
        sections."sec5".risks = SortUtil.sortIssuesByProperties(risks, ["requirementsKey", "key"])
        sections."sec5".proposedMeasures = SortUtil.sortIssuesByKey(proposedMeasuresDesription)

        def metadata = this.getDocumentMetadata(this.DOCUMENT_TYPE_NAMES[documentType])
        metadata.orientation = "Landscape"

        def keysInDoc = this.project.getRisks()
            .collect { it.subMap(['key', 'requirements', 'techSpecs', 'mitigations', 'tests']).values()  }
            .flatten()

        def docHistory = this.getAndStoreDocumentHistory(documentType, keysInDoc)

        def data_ = [
            metadata: metadata,
            data    : [
                sections: sections,
                documentHistory: docHistory?.getDocGenFormat() ?: [],
            ]
        ]

        def uri = this.createDocument(documentType, null, data_, [:], null, getDocumentTemplateName(documentType), watermarkText)
        this.updateJiraDocumentationTrackingIssue(documentType, uri, docHistory?.getVersion() as String)
        return uri
    }

    String createIVP(Map repo = null, Map data = null) {
        def documentType = DocumentType.IVP as String

        def sections = this.getDocumentSections(documentType)
        def watermarkText = this.getWatermarkText(documentType, this.project.hasWipJiraIssues())

        def installationTestIssues = this.project.getAutomatedTestsTypeInstallation()

        def testsGroupedByRepoType = groupTestsByRepoType(installationTestIssues)

        def testsOfRepoTypeOdsCode = []
        def testsOfRepoTypeOdsService = []
        testsGroupedByRepoType.each { repoTypes, tests ->
            if (repoTypes.contains(MROPipelineUtil.PipelineConfig.REPO_TYPE_ODS_CODE)) {
                testsOfRepoTypeOdsCode.addAll(tests)
            }

            if (repoTypes.contains(MROPipelineUtil.PipelineConfig.REPO_TYPE_ODS_SERVICE)) {
                testsOfRepoTypeOdsService.addAll(tests)
            }
        }

        def keysInDoc = installationTestIssues
            .collect { it.subMap(['key', 'components', 'techSpecs']).values()  }
            .flatten()
        def docHistory = this.getAndStoreDocumentHistory(documentType, keysInDoc)

        def data_ = [
            metadata: this.getDocumentMetadata(DOCUMENT_TYPE_NAMES[documentType]),
            data    : [
                repositories   : this.project.repositories.collect { [id: it.id, type: it.type, data: [git: [url: it.data.git == null ? null : it.data.git.url]]] },
                sections       : sections,
                tests          : SortUtil.sortIssuesByKey(installationTestIssues.collect { testIssue ->
                    [
                        key     : testIssue.key,
                        summary : testIssue.name,
                        techSpec: testIssue.techSpecs.join(", ") ?: "N/A"
                    ]
                }),
                testsOdsService: testsOfRepoTypeOdsService,
                testsOdsCode   : testsOfRepoTypeOdsCode
            ],
            documentHistory: docHistory?.getDocGenFormat() ?: [],
        ]

        def uri = this.createDocument(documentType, null, data_, [:], null, getDocumentTemplateName(documentType), watermarkText)
        this.updateJiraDocumentationTrackingIssue(documentType, uri, docHistory?.getVersion() as String)
        return uri
    }

    String createIVR(Map repo, Map data) {
        def documentType = DocumentType.IVR as String

        def installationTestData = data.tests.installation

        def sections = this.getDocumentSections(documentType)
        def watermarkText = this.getWatermarkText(documentType, this.project.hasWipJiraIssues())

        def installationTestIssues = this.project.getAutomatedTestsTypeInstallation()
        def discrepancies = this.computeTestDiscrepancies("Installation Tests", installationTestIssues, installationTestData.testResults)

        def testsOfRepoTypeOdsCode = []
        def testsOfRepoTypeOdsService = []
        def testsGroupedByRepoType = groupTestsByRepoType(installationTestIssues)
        testsGroupedByRepoType.each { repoTypes, tests ->
            if (repoTypes.contains(MROPipelineUtil.PipelineConfig.REPO_TYPE_ODS_CODE)) {
                testsOfRepoTypeOdsCode.addAll(tests)
            }

            if (repoTypes.contains(MROPipelineUtil.PipelineConfig.REPO_TYPE_ODS_SERVICE)) {
                testsOfRepoTypeOdsService.addAll(tests)
            }
        }

        def keysInDoc = installationTestIssues
            .collect { it.subMap(['key', 'components', 'techSpecs']).values()  }
            .flatten()
        def docHistory = this.getAndStoreDocumentHistory(documentType, keysInDoc)

        def data_ = [
            metadata: this.getDocumentMetadata(this.DOCUMENT_TYPE_NAMES[documentType]),
            data    : [
                repositories      : this.project.repositories.collect { [id: it.id, type: it.type, data: [git: [url: it.data.git == null ? null : it.data.git.url]]] },
                sections          : sections,
                tests             : SortUtil.sortIssuesByKey(installationTestIssues.collect { testIssue ->
                    [
                        key        : testIssue.key,
                        description: testIssue.description ?: "",
                        remarks    : testIssue.isUnexecuted ? "Not executed" : "",
                        success    : testIssue.isSuccess ? "Y" : "N",
                        summary    : testIssue.name,
                        techSpec   : testIssue.techSpecs.join(", ") ?: "N/A"
                    ]
                }),
                numAdditionalTests: junit.getNumberOfTestCases(installationTestData.testResults) - installationTestIssues.count { !it.isUnexecuted },
                testFiles         : SortUtil.sortIssuesByProperties(installationTestData.testReportFiles.collect { file ->
                    [name: file.name, path: file.path, text: file.text]
                } ?: [], ["name"]),
                discrepancies     : discrepancies.discrepancies,
                conclusion        : [
                    summary  : discrepancies.conclusion.summary,
                    statement: discrepancies.conclusion.statement
                ],
                testsOdsService   : testsOfRepoTypeOdsService,
                testsOdsCode      : testsOfRepoTypeOdsCode
            ],
            documentHistory: docHistory?.getDocGenFormat() ?: [],
        ]

        def files = data.tests.installation.testReportFiles.collectEntries { file ->
            ["raw/${file.getName()}", file.getBytes()]
        }

        def uri = this.createDocument(documentType, null, data_, files, null, getDocumentTemplateName(documentType), watermarkText)
        this.updateJiraDocumentationTrackingIssue(documentType, uri, docHistory?.getVersion() as String)
        return uri
    }

    @SuppressWarnings('CyclomaticComplexity')
    String createTCR(Map repo = null, Map data = null) {
        String documentType = DocumentType.TCR as String

        def sections = this.getDocumentSections(documentType)
        def watermarkText = this.getWatermarkText(documentType, this.project.hasWipJiraIssues())

        def integrationTestData = data.tests.integration
        def integrationTestIssues = this.project.getAutomatedTestsTypeIntegration()

        def acceptanceTestData = data.tests.acceptance
        def acceptanceTestIssues = this.project.getAutomatedTestsTypeAcceptance()

        def matchedHandler = { result ->
            result.each { testIssue, testCase ->
                testIssue.isSuccess = !(testCase.error || testCase.failure || testCase.skipped
                    || !testIssue.getResolvedBugs(). findAll { bug -> bug.status?.toLowerCase() != "done" }.isEmpty()
                    || testIssue.isUnexecuted)
                testIssue.comment = testIssue.isUnexecuted ? "This Test Case has not been executed" : ""
                testIssue.timestamp = testIssue.isUnexecuted ? "N/A" : testCase.timestamp
                testIssue.isUnexecuted = false
                testIssue.actualResult = testIssue.isSuccess ? "Expected result verified by automated test" :
                                         testIssue.isUnexecuted ? "Not executed" : "Test failed. Correction will be tracked by Jira issue task \"bug\" listed below."
            }
        }

        def unmatchedHandler = { result ->
            result.each { testIssue ->
                testIssue.isSuccess = false
                testIssue.isUnexecuted = true
                testIssue.comment = testIssue.isUnexecuted ? "This Test Case has not been executed" : ""
                testIssue.actualResult = !testIssue.isUnexecuted ? "Test failed. Correction will be tracked by Jira issue task \"bug\" listed below." : "Not executed"
            }
        }

        this.jiraUseCase.matchTestIssuesAgainstTestResults(integrationTestIssues, integrationTestData?.testResults ?: [:], matchedHandler, unmatchedHandler)
        this.jiraUseCase.matchTestIssuesAgainstTestResults(acceptanceTestIssues, acceptanceTestData?.testResults ?: [:], matchedHandler, unmatchedHandler)

        def keysInDoc = (integrationTestIssues + acceptanceTestIssues)
            .collect { it.subMap(['key', 'requirements', 'bugs']).values() }.flatten()

        def docHistory = this.getAndStoreDocumentHistory(documentType, keysInDoc)

        def data_ = [
            metadata: this.getDocumentMetadata(DOCUMENT_TYPE_NAMES[documentType]),
            data    : [
                sections            : sections,
                integrationTests    : SortUtil.sortIssuesByKey(integrationTestIssues.collect { testIssue ->
                    [
                        key         : testIssue.key,
                        description : testIssue.description,
                        requirements: testIssue.requirements ? testIssue.requirements.join(", ") : "N/A",
                        isSuccess   : testIssue.isSuccess,
                        bugs        : testIssue.bugs ? testIssue.bugs.join(", ") : (testIssue.comment ? "": "N/A"),
                        steps       : testIssue.steps,
                        timestamp   : testIssue.timestamp ? testIssue.timestamp.replaceAll("T", " ") : "N/A",
                        comment     : testIssue.comment,
                        actualResult: testIssue.actualResult
                    ]
                }),
                acceptanceTests     : SortUtil.sortIssuesByKey(acceptanceTestIssues.collect { testIssue ->
                    [
                        key         : testIssue.key,
                        description : testIssue.description,
                        requirements: testIssue.requirements ? testIssue.requirements.join(", ") : "N/A",
                        isSuccess   : testIssue.isSuccess,
                        bugs        : testIssue.bugs ? testIssue.bugs.join(", ") : (testIssue.comment ? "": "N/A"),
                        steps       : testIssue.steps,
                        timestamp   : testIssue.timestamp ? testIssue.timestamp.replaceAll("T", " ") : "N/A",
                        comment     : testIssue.comment,
                        actualResult: testIssue.actualResult
                    ]
                }),
                integrationTestFiles: SortUtil.sortIssuesByProperties(integrationTestData.testReportFiles.collect { file ->
                    [name: file.name, path: file.path, text: file.text]
                } ?: [], ["name"]),
                acceptanceTestFiles : SortUtil.sortIssuesByProperties(acceptanceTestData.testReportFiles.collect { file ->
                    [name: file.name, path: file.path, text: file.text]
                } ?: [], ["name"]),
                documentHistory: docHistory?.getDocGenFormat() ?: [],
            ]
        ]

        def uri = this.createDocument(documentType, null, data_, [:], null, getDocumentTemplateName(documentType), watermarkText)
        this.updateJiraDocumentationTrackingIssue(documentType, uri, docHistory?.getVersion() as String)
        return uri
    }

    String createTCP(Map repo = null, Map data = null) {
        String documentType = DocumentType.TCP as String

        def sections = this.getDocumentSections(documentType)
        def watermarkText = this.getWatermarkText(documentType, this.project.hasWipJiraIssues())

        def integrationTestIssues = this.project.getAutomatedTestsTypeIntegration()
        def acceptanceTestIssues = this.project.getAutomatedTestsTypeAcceptance()

        def keysInDoc = computeDocumentKeys(integrationTestIssues, acceptanceTestIssues)

        def docHistory = this.getAndStoreDocumentHistory(documentType, keysInDoc)
        def data_ = [
            metadata: this.getDocumentMetadata(DOCUMENT_TYPE_NAMES[documentType]),
            data    : [
                sections        : sections,
                integrationTests: SortUtil.sortIssuesByKey(integrationTestIssues.collect { testIssue ->
                    [
                        key         : testIssue.key,
                        description : testIssue.description,
                        requirements: testIssue.requirements ? testIssue.requirements.join(", ") : "N/A",
                        bugs        : testIssue.bugs ? testIssue.bugs.join(", ") : "N/A",
                        steps       : testIssue.steps
                    ]
                }),
                acceptanceTests : SortUtil.sortIssuesByKey(acceptanceTestIssues.collect { testIssue ->
                    [
                        key         : testIssue.key,
                        description : testIssue.description,
                        requirements: testIssue.requirements ? testIssue.requirements.join(", ") : "N/A",
                        bugs        : testIssue.bugs ? testIssue.bugs.join(", ") : "N/A",
                        steps       : testIssue.steps
                    ]
                }),
                documentHistory: docHistory?.getDocGenFormat() ?: [],
            ]
        ]

        def uri = this.createDocument(documentType, null, data_, [:], null, getDocumentTemplateName(documentType), watermarkText)
        this.updateJiraDocumentationTrackingIssue(documentType, uri, docHistory?.getVersion() as String)
        return uri
    }

    String createSSDS(Map repo = null, Map data = null) {
        def documentType = DocumentType.SSDS as String

        def sections = this.getDocumentSections(documentType)
        def watermarkText = this.getWatermarkText(documentType, this.project.hasWipJiraIssues())

        def componentsMetadata = SortUtil.sortIssuesByKey(this.computeComponentMetadata(documentType).values())
        def systemDesignSpecifications = this.project.getTechnicalSpecifications()
            .findAll { it.systemDesignSpec }
            .collect { techSpec ->
                [
                    key        : techSpec.key,
                    req_key    : techSpec.requirements?.join(", ") ?: "None",
                    description: this.convertImages(techSpec.systemDesignSpec)
                ]
            }

        if (!sections."sec3s1") sections."sec3s1" = [:]
        sections."sec3s1".specifications = SortUtil.sortIssuesByProperties(systemDesignSpecifications, ["req_key", "key"])

        if (!sections."sec5s1") sections."sec5s1" = [:]
        sections."sec5s1".components = componentsMetadata.collect { c ->
            [
                key           : c.key,
                nameOfSoftware: c.nameOfSoftware,
                componentType : c.componentType,
                componentId   : c.componentId,
                description   : c.description,
                supplier      : c.supplier,
                version       : c.version,
                references    : c.references
            ]
        }

        // Get the components that we consider modules in SSDS (the ones you have to code)
        def modules = componentsMetadata
            .findAll { it.odsRepoType.toLowerCase() == MROPipelineUtil.PipelineConfig.REPO_TYPE_ODS_CODE.toLowerCase() }
            .collect { component ->
                // We will set-up a double loop in the template. For moustache limitations we need to have lists
                component.requirements = component.requirements.collect { r ->
                    [key: r.key, name: r.name,
                     reqDescription: this.convertImages(r.description), gampTopic: r.gampTopic ?: "uncategorized"]
                }.groupBy { it.gampTopic.toLowerCase() }
                    .collect { k, v -> [gampTopic: k, requirementsofTopic: v] }

                return component
        }

        if (!sections."sec10") sections."sec10" = [:]
        sections."sec10".modules = modules

        // Code review report
        def codeRepos = this.project.repositories. findAll { it.type?.toLowerCase() == MROPipelineUtil.PipelineConfig.REPO_TYPE_ODS_CODE.toLowerCase() }
        def codeReviewReports = obtainCodeReviewReport(codeRepos)

        def modifier = { document ->
            List documents = [document]
            documents += codeReviewReports
            // Merge the current document with the code review report
            return this.pdf.merge(documents)
        }

        def keysInDoc = (this.project.getTechnicalSpecifications()
            .collect { it.subMap(['key', 'requirements']).values() }.flatten()
        + componentsMetadata.collect { it.key }
        + modules.collect { it.subMap(['requirementKeys', 'softwareDesignSpecKeys']).values() }.flatten())

        def docHistory = this.getAndStoreDocumentHistory(documentType, keysInDoc)
        def data_ = [
            metadata: this.getDocumentMetadata(this.DOCUMENT_TYPE_NAMES[documentType], repo),
            data    : [
                sections: sections,
                documentHistory: docHistory?.getDocGenFormat() ?: [],
            ]
        ]

        def uri = this.createDocument(documentType, null, data_, [:], modifier, getDocumentTemplateName(documentType), watermarkText)
        this.updateJiraDocumentationTrackingIssue(documentType, uri, docHistory?.getVersion() as String)
        return uri
    }

    String createTIP(Map repo = null, Map data = null) {
        def documentType = DocumentType.TIP as String

        def sections = this.getDocumentSectionsFileOptional(documentType)
        def watermarkText = this.getWatermarkText(documentType, this.project.hasWipJiraIssues())

        def keysInDoc = this.project.getComponents().collect {it.key }
        def docHistory = this.getAndStoreDocumentHistory(documentType, keysInDoc)

        def data_ = [
            metadata: this.getDocumentMetadata(this.DOCUMENT_TYPE_NAMES[documentType]),
            data    : [
                project_key : this.project.key,
                repositories: this.project.repositories,
                sections    : sections,
                documentHistory: docHistory?.getDocGenFormat() ?: [],
            ]
        ]

        def uri = this.createDocument(documentType, null, data_, [:], null, getDocumentTemplateName(documentType), watermarkText)
        this.updateJiraDocumentationTrackingIssue(documentType, uri, docHistory?.getVersion() as String)
        return uri
    }

    @SuppressWarnings('CyclomaticComplexity')
    String createTIR(Map repo, Map data) {
        def documentType = DocumentType.TIR as String

        def installationTestData = data?.tests?.installation

        def sections = this.getDocumentSectionsFileOptional(documentType)
        def watermarkText = this.getWatermarkText(documentType, this.project.hasWipJiraIssues())

        def deploynoteData = 'Components were built & deployed during installation.'
        if (repo.data.openshift.resurrectedBuild) {
            deploynoteData = "Components were found, and are 'up to date' with version control -no deployments happend!\r" +
                " SCRR was restored from the corresponding creation build (${repo.data.openshift.resurrectedBuild})"
        } else if (!repo.data.openshift.builds) {
            deploynoteData = 'NO Components were built during installation, existing components (created in Dev) were deployed.'
        }

        def keysInDoc = ['Technology-' + repo.id]
        def docHistory = this.getAndStoreDocumentHistory(documentType + '-' + repo.id, keysInDoc)

        def data_ = [
            metadata     : this.getDocumentMetadata(this.DOCUMENT_TYPE_NAMES[documentType], repo),
            deployNote   : deploynoteData,
            openShiftData: [
                builds     : repo.data.openshift.builds ?: '',
                deployments: repo.data.openshift.deployments ?: ''
            ],
            testResults: [
                installation: installationTestData?.testResults
            ],
            data: [
                repo    : repo,
                sections: sections,
                documentHistory: docHistory?.getDocGenFormat() ?: [],
            ]
        ]

        // Code review report - in the special case of NO jira ..
        def codeReviewReport
        if (this.project.isAssembleMode && !this.jiraUseCase.jira &&
            repo.type?.toLowerCase() == MROPipelineUtil.PipelineConfig.REPO_TYPE_ODS_CODE.toLowerCase()) {
            def currentRepoAsList = [ repo ]
            codeReviewReport = obtainCodeReviewReport(currentRepoAsList)
        }

        def modifier = { document ->
            if (codeReviewReport) {
                List documents = [document]
                documents += codeReviewReport
                // Merge the current document with the code review report
                document = this.pdf.merge(documents)
            }
            return document
        }

        return this.createDocument(documentType, repo, data_, [:], modifier, getDocumentTemplateName(documentType, repo), watermarkText)
    }

    String createOverallTIR(Map repo = null, Map data = null) {
        def documentTypeName = DOCUMENT_TYPE_NAMES[DocumentType.OVERALL_TIR as String]
        def metadata = this.getDocumentMetadata(documentTypeName)

        def documentType = DocumentType.TIR as String

        def watermarkText = this.getWatermarkText(documentType, this.project.hasWipJiraIssues())

        def visitor = { data_ ->
            // Prepend a section for the Jenkins build log
            data_.sections.add(0, [
                heading: 'Installed Component Summary'
            ])
            data_.sections.add(1, [
                heading: 'Jenkins Build Log'
            ])

            // Add Jenkins build log data
            data_.jenkinsData = [
                log: this.jenkins.getCurrentBuildLogAsText()
            ]

            data_.repositories = this.project.repositories
        }

        def uri = this.createOverallDocument('Overall-TIR-Cover', documentType, metadata, visitor, watermarkText)
        def docHistory = this.project.findHistoryForDocumentType(documentType)
        this.updateJiraDocumentationTrackingIssue(documentType, uri, docHistory?.getVersion() as String)
        return uri
    }

    String createTRC(Map repo, Map data) {
        def documentType = DocumentType.TRC as String

        def acceptanceTestData = data.tests.acceptance
        def installationTestData = data.tests.installation
        def integrationTestData = data.tests.integration

        def sections = this.getDocumentSections(documentType)
        def systemRequirements = this.project.getSystemRequirements()

        // Compute the test issues we do not consider done (not successful)
        def testIssues = systemRequirements.collect { it.getResolvedTests() }.flatten().unique().findAll {
            [Project.TestType.ACCEPTANCE, Project.TestType.INSTALLATION, Project.TestType.INTEGRATION].contains(it.testType)
        }

        this.computeTestDiscrepancies(null, testIssues, junit.combineTestResults([acceptanceTestData.testResults, installationTestData.testResults, integrationTestData.testResults]))

        def testIssuesWip = testIssues.findAll { !it.status.equalsIgnoreCase("cancelled") && (!it.isSuccess || it.isUnexecuted) }

        def hasFailingTestIssues = !testIssuesWip.isEmpty()

        def watermarkText = this.getWatermarkText(documentType, hasFailingTestIssues || this.project.hasWipJiraIssues())

        systemRequirements = systemRequirements.collect { r ->
            def predecessors = r.expandedPredecessors.collect { [key: it.key, versions: it.versions.join(', ')] }
            [
                key         : r.key,
                name        : r.name,
                description : r.description,
                risks       : r.risks.join(", "),
                tests       : r.tests.join(", "),
                predecessors: predecessors,
            ]
        }

        if (!sections."sec4") sections."sec4" = [:]
        sections."sec4".systemRequirements = SortUtil.sortIssuesByKey(systemRequirements)
        def keysInDoc = this.project.getSystemRequirements()
            .collect { it.subMap(['key', 'risks', 'tests']).values()  }
            .flatten()

        def docHistory = this.getAndStoreDocumentHistory(documentType, keysInDoc)

        def data_ = [
            metadata: this.getDocumentMetadata(this.DOCUMENT_TYPE_NAMES[documentType], repo),
            data    : [
                sections: sections,
                documentHistory: docHistory?.getDocGenFormat() ?: [],
            ]
        ]

        def uri = this.createDocument(documentType, null, data_, [:], null, getDocumentTemplateName(documentType), watermarkText)
        this.updateJiraDocumentationTrackingIssue(documentType, uri, docHistory?.getVersion() as String)
        return uri
    }

    String getDocumentTemplateName(String documentType, Map repo = null) {
        def capability = this.project.getCapability("LeVADocs")
        if (!capability) {
            return documentType
        }

        def suffix = ""
        // compute suffix based on repository type
        if (repo != null) {
            if (repo.type.toLowerCase() == MROPipelineUtil.PipelineConfig.REPO_TYPE_ODS_INFRA) {
                if (documentType == DocumentType.TIR as String) {
                    suffix += "-infra"
                }
            }
        }

        // compute suffix based on gamp category
        if (this.GAMP_CATEGORY_SENSITIVE_DOCS.contains(documentType)) {
            suffix += "-" + capability.GAMPCategory
        }

        return documentType + suffix
    }

    @NonCPS
    private def computeDocumentKeys(integrationTestIssues, acceptanceTestIssues) {
        return (integrationTestIssues + acceptanceTestIssues)
            .collect { it.subMap(['key', 'requirements', 'bugs']).values() }.flatten()
    }

    List<String> getSupportedDocuments() {
        return DocumentType.values().collect { it as String }
    }

    String getDocumentTemplatesVersion() {
        def capability = this.project.getCapability('LeVADocs')
        return capability.templatesVersion
    }

    boolean shouldCreateArtifact (String documentType, Map repo) {
        List nonArtifactDocTypes = [
            DocumentType.TIR as String,
            DocumentType.DTR as String
        ]

        return !(documentType && nonArtifactDocTypes.contains(documentType) && repo)
    }

    Map getFiletypeForDocumentType (String documentType) {
        if (!documentType) {
            throw new RuntimeException ('Cannot lookup Null docType for storage!')
        }
        Map defaultTypes = [storage: 'zip', content: 'pdf' ]

        if (DOCUMENT_TYPE_NAMES.containsKey(documentType)) {
            return defaultTypes
        } else if (DOCUMENT_TYPE_FILESTORAGE_EXCEPTIONS.containsKey(documentType)) {
            return DOCUMENT_TYPE_FILESTORAGE_EXCEPTIONS.get(documentType)
        }
        return defaultTypes
    }

    protected String convertImages(String content) {
        def result = content
        if (content && content.contains("<img")) {
            result = this.jiraUseCase.convertHTMLImageSrcIntoBase64Data(content)
        }
        result
    }

    protected Map computeTestDiscrepancies(String name, List testIssues, Map testResults) {
        def result = [
            discrepancies: 'No discrepancies found.',
            conclusion   : [
                summary  : 'Complete success, no discrepancies',
                statement: "It is determined that all steps of the ${name} have been successfully executed and signature of this report verifies that the tests have been performed according to the plan. No discrepancies occurred.",
            ]
        ]

        // Match Jira test issues with test results
        def matchedHandler = { matched ->
            matched.each { testIssue, testCase ->
                testIssue.isSuccess = !(testCase.error || testCase.failure || testCase.skipped)
                testIssue.isUnexecuted = !!testCase.skipped
                testIssue.timestamp = testCase.timestamp
            }
        }

        def unmatchedHandler = { unmatched ->
            unmatched.each { testIssue ->
                testIssue.isSuccess = false
                testIssue.isUnexecuted = true
            }
        }

        this.jiraUseCase.matchTestIssuesAgainstTestResults(testIssues, testResults ?: [:], matchedHandler, unmatchedHandler)

        // Compute failed and missing Jira test issues
        def failedTestIssues = testIssues.findAll { testIssue ->
            return !testIssue.isSuccess && !testIssue.isUnexecuted
        }

        def unexecutedTestIssues = testIssues.findAll { testIssue ->
            return !testIssue.isSuccess && testIssue.isUnexecuted
        }

        // Compute extraneous failed test cases
        def extraneousFailedTestCases = []
        testResults.testsuites.each { testSuite ->
            extraneousFailedTestCases.addAll(testSuite.testcases.findAll { testCase ->
                return (testCase.error || testCase.failure) && !failedTestIssues.any { this.jiraUseCase.checkTestsIssueMatchesTestCase(it, testCase) }
            })
        }

        // Compute test discrepancies
        def isMajorDiscrepancy = failedTestIssues || unexecutedTestIssues || extraneousFailedTestCases
        if (isMajorDiscrepancy) {
            result.discrepancies = 'The following major discrepancies were found during testing.'
            result.conclusion.summary = 'No success - major discrepancies found'
            result.conclusion.statement = 'Some discrepancies found as'

            if (failedTestIssues || extraneousFailedTestCases) {
                result.conclusion.statement += ' tests did fail'
            }

            if (failedTestIssues) {
                result.discrepancies += " Failed tests: ${failedTestIssues.collect { it.key }.join(', ')}."
            }

            if (extraneousFailedTestCases) {
                result.discrepancies += " Other failed tests: ${extraneousFailedTestCases.size()}."
            }

            if (unexecutedTestIssues) {
                result.discrepancies += " Unexecuted tests: ${unexecutedTestIssues.collect { it.key }.join(', ')}."

                if (failedTestIssues || extraneousFailedTestCases) {
                    result.conclusion.statement += ' and others were not executed'
                } else {
                    result.conclusion.statement += ' tests were not executed'
                }
            }

            result.conclusion.statement += '.'
        }

        return result
    }

    protected List<Map> computeTestsWithRequirementsAndSpecs(List<Map> tests) {
        def obtainEnum = { category, value ->
            return this.project.getEnumDictionary(category)[value as String]
        }

        tests.collect { testIssue ->
            def softwareDesignSpecs = testIssue.getResolvedTechnicalSpecifications()
                .findAll { it.softwareDesignSpec }
                .collect { it.key }
            def riskLevels = testIssue.getResolvedRisks(). collect {
                def value = obtainEnum("SeverityOfImpact", it.severityOfImpact)
                return value ? value.text : "None"
            }

            [
                moduleName: testIssue.components.join(", "),
                testKey: testIssue.key,
                description: testIssue.description ?: "N/A",
                systemRequirement: testIssue.requirements ? testIssue.requirements.join(", ") : "N/A",
                softwareDesignSpec: (softwareDesignSpecs.join(", ")) ?: "N/A",
                riskLevel: riskLevels ? riskLevels.join(", ") : "N/A"
            ]
        }
    }

    protected List obtainCodeReviewReport(List<Map> repos) {
        def reports =  repos.collect { r ->
            // resurrect?
            Map resurrectedDocument = resurrectAndStashDocument('SCRR-MD', r, false)
            this.steps.echo "Resurrected 'SCRR' for ${r.id} -> (${resurrectedDocument.found})"
            if (resurrectedDocument.found) {
                return resurrectedDocument.content
            }

            def sqReportsPath = "${PipelineUtil.SONARQUBE_BASE_DIR}/${r.id}"
            def sqReportsStashName = "scrr-report-${r.id}-${this.steps.env.BUILD_ID}"

            // Unstash SonarQube reports into path
            def hasStashedSonarQubeReports = this.jenkins.unstashFilesIntoPath(sqReportsStashName, "${this.steps.env.WORKSPACE}/${sqReportsPath}", "SonarQube Report")
            if (!hasStashedSonarQubeReports) {
                throw new RuntimeException("Error: unable to unstash SonarQube reports for repo '${r.id}' from stash '${sqReportsStashName}'.")
            }

            // Load SonarQube report files from path
            def sqReportFiles = this.sq.loadReportsFromPath("${this.steps.env.WORKSPACE}/${sqReportsPath}")
            if (sqReportFiles.isEmpty()) {
                throw new RuntimeException("Error: unable to load SonarQube reports for repo '${r.id}' from path '${this.steps.env.WORKSPACE}/${sqReportsPath}'.")
            }

            def name = this.getDocumentBasename('SCRR-MD', this.project.buildParams.version, this.steps.env.BUILD_ID, r)
            def sqReportFile = sqReportFiles.first()

            def generatedSCRR = this.pdf.convertFromMarkdown(sqReportFile, true)

            // store doc - we may need it later for partial deployments
            if (!resurrectedDocument.found) {
                def result = this.storeDocument("${name}.pdf", generatedSCRR, 'application/pdf')
                this.steps.echo "Stored 'SCRR' for later consumption -> ${result}"
            }
            return generatedSCRR
        }

        return reports
    }

    /**
     * This computes the information related to the components (modules) that are being developed
     * @param documentType
     * @return component metadata with software design specs, requirements and info comming from the component repo
     */
    protected Map computeComponentMetadata(String documentType) {
        return this.project.components.collectEntries { component ->
            def normComponentName = component.name.replaceAll('Technology-', '')

            def gitUrl = new GitService(
                this.steps, new Logger(this.steps, false)).getOriginUrl()
            def isReleaseManagerComponent =
                gitUrl.endsWith("${this.project.key}-${normComponentName}.git".toLowerCase())
            if (isReleaseManagerComponent) {
                return [ : ]
            }

            def repo_ = this.project.repositories.find {
                [it.id, it.name, it.metadata.name].contains(normComponentName)
            }
            if (!repo_) {
                def repoNamesAndIds = this.project.repositories. collect { [id: it.id, name: it.name] }
                throw new RuntimeException("Error: unable to create ${documentType}. Could not find a repository " +
                    "configuration with id or name equal to '${normComponentName}' for " +
                    "Jira component '${component.name}' in project '${this.project.key}'. Please check " +
                    "the metatada.yml file. In this file there are the following repositories " +
                    "configured: ${repoNamesAndIds}")
            }

            def metadata = repo_.metadata

            def sowftwareDesignSpecs = component.getResolvedTechnicalSpecifications()
                .findAll { it.softwareDesignSpec }
                .collect { [key: it.key, softwareDesignSpec: this.convertImages(it.softwareDesignSpec)] }

            return [
                (component.name): [
                    key               : component.name,
                    componentName     : component.name,
                    componentId       : metadata.id ?: 'N/A - part of this application',
                    componentType     : (repo_.type?.toLowerCase() == MROPipelineUtil.PipelineConfig.REPO_TYPE_ODS_CODE) ? 'ODS Component' : 'Software',
                    odsRepoType       : repo_.type?.toLowerCase(),
                    description       : metadata.description,
                    nameOfSoftware    : normComponentName ?: metadata.name,
                    references        : metadata.references ?: 'N/A',
                    supplier          : metadata.supplier,
                    version           : (repo_.type?.toLowerCase() == MROPipelineUtil.PipelineConfig.REPO_TYPE_ODS_CODE) ?
                        this.project.buildParams.version :
                        metadata.version,
                    requirements      : component.getResolvedSystemRequirements(),
                    requirementKeys   : component.requirements,
                    softwareDesignSpecKeys: sowftwareDesignSpecs.collect { it.key },
                    softwareDesignSpec: sowftwareDesignSpecs
                ]
            ]
        }
    }

    protected Map computeComponentsUnitTests(List<Map> tests) {
        def issueComponentMapping = tests.collect { test ->
            test.getResolvedComponents().collect { [test: test.key, component: it.name] }
        }.flatten()
        issueComponentMapping.groupBy { it.component }.collectEntries { c, v ->
            [(c.replaceAll("Technology-", "")): v.collect { it.test } ]
        }
    }

    protected List<Map> getReposWithUnitTestsInfo(List<Map> unitTests) {
        def componentTestMapping = computeComponentsUnitTests(unitTests)
        this.project.repositories.collect {
            [
                id: it.id,
                description: it.metadata.description,
                tests: componentTestMapping[it.id]? componentTestMapping[it.id].join(", "): "None defined"
            ]
        }
    }

    private Map groupTestsByRepoType(List jiraTestIssues) {
        return jiraTestIssues.collect { test ->
            def components = test.getResolvedComponents()
            test.repoTypes = components.collect { component ->
                def normalizedComponentName = component.name.replaceAll('Technology-', '')
                def repository = this.project.repositories.find { repository ->
                    [repository.id, repository.name].contains(normalizedComponentName)
                }

                if (!repository) {
                    throw new IllegalArgumentException("Error: unable to find a repository definition with id or name equal to '${normalizedComponentName}' for Jira component '${component.name}' in project '${this.project.id}'.")
                }

                return repository.type
            } as Set

            return test
        }.groupBy { it.repoTypes }
    }

    protected Map getDocumentMetadata(String documentTypeName, Map repo = null) {
        def name = this.project.name
        if (repo) {
            name += ": ${repo.id}"
        }

        def metadata = [
            id            : null, // unused
            name          : name,
            description   : this.project.description,
            type          : documentTypeName,
            version       : this.steps.env.RELEASE_PARAM_VERSION,
            date_created  : LocalDateTime.now().toString(),
            buildParameter: this.project.buildParams,
            git           : repo ? repo.data.git : this.project.gitData,
            openShift     : [apiUrl: this.project.getOpenShiftApiUrl()],
            jenkins       : [
                buildNumber: this.steps.env.BUILD_NUMBER,
                buildUrl   : this.steps.env.BUILD_URL,
                jobName    : this.steps.env.JOB_NAME
            ],
            referencedDocs : this.getReferencedDocumentsVersion()
        ]

        metadata.header = ["${documentTypeName}, Config Item: ${metadata.buildParameter.configItem}", "Doc ID/Version: see auto-generated cover page"]

        return metadata
    }

    private List<String> getJiraTrackingIssueLabelsForDocTypeAndEnvs(String documentType, List<String> envs = null) {
        def labels = []

        def environments = (envs)? envs : this.project.buildParams.targetEnvironmentToken
        environments.each { env ->
            LeVADocumentScheduler.ENVIRONMENT_TYPE[env].get(documentType).each { label ->
                labels.add("${JiraUseCase.LabelPrefix.DOCUMENT}${label}")
            }
        }

        if (this.project.isDeveloperPreviewMode()) {
            // Assumes that every document we generate along the pipeline has a tracking issue in Jira
            labels.add("${JiraUseCase.LabelPrefix.DOCUMENT}${documentType}")
        }

        return labels
    }

    protected String getWatermarkText(String documentType, boolean hasWipJiraIssues) {
        def result = null

        if (this.project.isDeveloperPreviewMode()) {
            result = this.DEVELOPER_PREVIEW_WATERMARK
        }

        if (hasWipJiraIssues) {
            result = this.WORK_IN_PROGRESS_WATERMARK
        }

        return result
    }

    protected void updateJiraDocumentationTrackingIssue(String documentType,
                                                        String docLocation,
                                                        String documentVersionId = null) {
        if (!this.jiraUseCase) return
        if (!this.jiraUseCase.jira) return

        def jiraIssues = this.getDocumentTrackingIssues(documentType)
        def msg = "A new ${DOCUMENT_TYPE_NAMES[documentType]} has been generated and is available at: ${docLocation}."
        def sectionsNotDone = this.getSectionsNotDone(documentType)
        // Append a warning message for documents which are considered work in progress
        if (!sectionsNotDone.isEmpty()) {
            msg += " ${WORK_IN_PROGRESS_DOCUMENT_MESSAGE} See issues:" +
                " ${sectionsNotDone.join(', ')}"
        }

        // Append a warning message if there are any open tasks. Documents will not be considered final
        // TODO review me
        if (documentVersionId && !this.project.isDeveloperPreviewMode() && this.project.hasWipJiraIssues()) {
            msg += "\n *Since there are WIP issues in Jira that affect one or more documents," +
                " this document cannot be considered final.*"
        }

        if (! documentVersionId) {
            def metadata = this.getDocumentMetadata(documentType)
            documentVersionId = "${metadata.version}-${metadata.jenkins.buildNumber}"
        }

        jiraIssues.each { Map jiraIssue ->
            this.updateValidDocVersionInJira(jiraIssue.key as String, documentVersionId)
            this.jiraUseCase.jira.appendCommentToIssue(jiraIssue.key as String, msg)
        }
    }

    protected List<String> getSectionsNotDone(String documentType) {
        return this.project.getWIPDocChaptersForDocument(documentType)
    }

    protected List<String> computeSectionsNotDone(Map issues = [:]) {
        if (!issues) return []
        return issues.values().findAll { !it.status?.equalsIgnoreCase('done') }.collect { it.key }
    }

    protected DocumentHistory getAndStoreDocumentHistory(String documentName, List<String> keysInDoc = []) {
        if (!this.jiraUseCase) return
        if (!this.jiraUseCase.jira) return
        // If we have already saved the version, load it from project
        if (this.project.historyForDocumentExists(documentName)) {

            return this.project.getHistoryForDocument(documentName)
        } else {
            def documentType = documentName.split('-').first()
            def jiraData = this.project.data.jira as Map
            def environment = this.computeSavedDocumentEnvironment(documentType)
            def latestValidVersionId = this.getLatestDocVersionId(documentType, [environment])
            def docHistory = new DocumentHistory(this.steps, new Logger(this.steps, false), environment, documentName)
            def docChapters = this.project.getDocumentChaptersForDocument(documentName)
            def docChapterKeys = docChapters.collect { chapter ->
                chapter.key
            }
            docHistory.load(jiraData, latestValidVersionId, (keysInDoc + docChapterKeys).unique())

            // Save the doc history to project class, so it can be persisted when considered
            this.project.setHistoryForDocument(docHistory, documentName)

            return docHistory
        }
    }

    protected String computeSavedDocumentEnvironment(String documentType) {
        def environment = this.project.buildParams.targetEnvironmentToken
        if (this.project.isWorkInProgress) {
            environment = ['D', 'Q', 'P'].find { env ->
                LeVADocumentScheduler.ENVIRONMENT_TYPE[env].containsKey(documentType)
            }
        }
        environment
    }

    protected void updateValidDocVersionInJira(String jiraIssueKey, String docVersionId) {
        def documentationTrackingIssueFields = this.project.getJiraFieldsForIssueType(JiraUseCase.IssueTypes.DOCUMENTATION_TRACKING)
        def documentationTrackingIssueDocumentVersionField = documentationTrackingIssueFields[JiraUseCase.CustomIssueFields.DOCUMENT_VERSION]

        if (this.project.isVersioningEnabled) {
            if (!this.project.isDeveloperPreviewMode() && !this.project.hasWipJiraIssues()) {
                // In case of generating a final document, we add the label for the version that should be released
                this.jiraUseCase.jira.updateTextFieldsOnIssue(jiraIssueKey,
                    [(documentationTrackingIssueDocumentVersionField.id): "${docVersionId}"])
            }
        } else {
            // TODO removeme for ODS 4.0
            this.jiraUseCase.jira.updateTextFieldsOnIssue(jiraIssueKey,
                [(documentationTrackingIssueDocumentVersionField.id): "${docVersionId}"])
        }
    }

    protected List<Map> getDocumentTrackingIssues(String documentType, List<String> environments = null) {
        def jiraDocumentLabels = this.getJiraTrackingIssueLabelsForDocTypeAndEnvs(documentType, environments)
        def jiraIssues = this.project.getDocumentTrackingIssues(jiraDocumentLabels)
        if (jiraIssues.isEmpty()) {
            throw new RuntimeException("Error: no Jira tracking issue associated with document type '${documentType}'.")
        }
        return jiraIssues
    }
    protected List<Map> getDocumentTrackingIssuesForHistory(String documentType, List<String> environments = null) {
        def jiraDocumentLabels = this.getJiraTrackingIssueLabelsForDocTypeAndEnvs(documentType, environments)
        def jiraIssues = this.project.getDocumentTrackingIssuesForHistory(jiraDocumentLabels)
        if (jiraIssues.isEmpty()) {
            throw new RuntimeException("Error: no Jira tracking issue associated with document type '${documentType}'.")
        }
        return jiraIssues
    }

    protected Map getDocumentSections(String documentType) {
        def sections = this.project.getDocumentChaptersForDocument(documentType)
        if (!sections) {
            throw new RuntimeException("Error: unable to create ${documentType}. " +
                'Could not obtain document chapter data from Jira.')
        }
        // Extract-out the section, as needed for the DocGen interface
        return sections.collectEntries { sec ->
            [(sec.section): sec + [content: this.convertImages(sec.content)]]
        }
    }

    protected Map getDocumentSectionsFileOptional(String documentType) {
        def sections = this.project.getDocumentChaptersForDocument(documentType)
        sections = sections?.collectEntries { sec ->
            [(sec.section): sec]
        }
        if (!sections || sections.isEmpty() ) {
            sections = this.levaFiles.getDocumentChapterData(documentType)
            if (!this.project.data.jira.undoneDocChapters) {
                this.project.data.jira.undoneDocChapters = [:]
            }
            this.project.data.jira.undoneDocChapters[documentType] = this.computeSectionsNotDone(sections)
        }
        if (!sections) {
            throw new RuntimeException("Error: unable to create ${documentType}. " +
                'Could not obtain document chapter data from Jira nor files.')
        }
        // Extract-out the section, as needed for the DocGen interface
        return sections
    }

    /**
     * Gets the valid or to be valid document version either from the current project (for documents created
     * together) or from Jira for documents generated in another environments.
     * @param document to be gathered the id of
     * @return string with the valid id
     */
    protected Long getLatestDocVersionId(String document, List<String> environments = null) {
        if (this.project.historyForDocumentExists(document)) {
            this.project.getHistoryForDocument(document).getVersion()
        } else {
            def trackingIssues =  this.getDocumentTrackingIssuesForHistory(document, environments)
            this.jiraUseCase.getLatestDocVersionId(trackingIssues)
        }
    }

    /**
     * gets teh document version IDS at the start ... can't do that...
     * @return
     */
    protected Map getReferencedDocumentsVersion() {
        if (!this.jiraUseCase) return [:]
        if (!this.jiraUseCase.jira) return [:]

        def environment = this.project.buildParams.targetEnvironmentToken
        def docIsCreatedInTheEnvironment = { String doc ->
            LeVADocumentScheduler.ENVIRONMENT_TYPE[environment].containsKey(doc)
        }

        def referencedDcocs = [
            DocumentType.CSD,
            DocumentType.SSDS,
            DocumentType.RA,
            DocumentType.TRC,
            DocumentType.DTP,
            DocumentType.DTR,
            DocumentType.CFTP,
            DocumentType.CFTR,
            DocumentType.TIR,
            DocumentType.TIP,
        ]

        referencedDcocs.collectEntries { DocumentType dt ->
            def doc = dt as String
            def version

            if (! this.project.isVersioningEnabled) {
                // TODO removeme in ODS 4.x
                version = "${this.project.buildParams.version}-${this.steps.env.BUILD_NUMBER}"
            } else if (this.project.historyForDocumentExists(doc)) {
                version = this.project.getHistoryForDocument(doc).getVersion()
            } else {
                def trackingIssues =  this.getDocumentTrackingIssues(doc, ['D', 'Q', 'P'])
                version = this.jiraUseCase.getLatestDocVersionId(trackingIssues)
                if (this.project.isWorkInProgress) {
                    version = (version + 1L).toString() + "-WIP"
                } else if (docIsCreatedInTheEnvironment(doc)) {
                    // The document will be generated in this deploy but it is not created yet
                    version += 1L
                }
            }

            [(doc): "${this.project.buildParams.configItem} / ${version}"]
        }

    }

}
