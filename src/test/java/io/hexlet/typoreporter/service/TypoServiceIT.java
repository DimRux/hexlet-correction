package io.hexlet.typoreporter.service;

import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.spring.api.DBRider;
import io.hexlet.typoreporter.domain.AbstractAuditingEntity;
import io.hexlet.typoreporter.domain.typo.Typo;
import io.hexlet.typoreporter.domain.typo.TypoStatus;
import io.hexlet.typoreporter.domain.workspace.Workspace;
import io.hexlet.typoreporter.repository.TypoRepository;
import io.hexlet.typoreporter.service.dto.typo.TypoInfo;
import io.hexlet.typoreporter.service.dto.typo.TypoReport;
import io.hexlet.typoreporter.test.DBUnitEnumPostgres;
import io.hexlet.typoreporter.test.asserts.ReportedTypoAssert;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.github.database.rider.core.api.configuration.Orthography.LOWERCASE;
import static io.hexlet.typoreporter.domain.typo.TypoEvent.START;
import static io.hexlet.typoreporter.domain.typo.TypoEvent.RESTART;
import static io.hexlet.typoreporter.domain.typo.TypoEvent.RESOLVE;
import static io.hexlet.typoreporter.domain.typo.TypoStatus.CANCELED;
import static io.hexlet.typoreporter.domain.typo.TypoStatus.IN_PROGRESS;
import static io.hexlet.typoreporter.domain.typo.TypoStatus.REPORTED;
import static io.hexlet.typoreporter.domain.typo.TypoStatus.RESOLVED;
import static io.hexlet.typoreporter.test.Constraints.POSTGRES_IMAGE;
import static io.hexlet.typoreporter.test.factory.EntitiesFactory.*;
import static java.util.function.Predicate.not;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@WithMockUser
@Testcontainers
@Transactional
@DBRider
@DBUnit(caseInsensitiveStrategy = LOWERCASE, dataTypeFactoryClass = DBUnitEnumPostgres.class, cacheConnection = false)
@DataSet(value = {"workspaces.yml", "typos.yml"})
class TypoServiceIT {

    @Container
    public static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>(POSTGRES_IMAGE)
        .withPassword("inmemory")
        .withUsername("inmemory");

    @Autowired
    private TypoRepository repository;

    @Autowired
    private TypoService service;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgreSQLContainer::getJdbcUrl);
        registry.add("spring.datasource.password", postgreSQLContainer::getPassword);
        registry.add("spring.datasource.username", postgreSQLContainer::getUsername);
    }

    @ParameterizedTest
    @MethodSource("io.hexlet.typoreporter.test.factory.EntitiesFactory#getTypoReport")
    void addTypoReport(final TypoReport report) {
        final var reportedTypo = service.addTypoReport(report, WORKSPACE_101_ID);

        ReportedTypoAssert.assertThat(reportedTypo).isEqualsToTypoReport(report);
        assertThat(repository.existsById(reportedTypo.id())).isTrue();
    }

    @Test
    void getPageTypo() {
        final var totalSize = repository.findAll()
            .stream()
            .map(Typo::getWorkspace)
            .map(Workspace::getId)
            .filter(WORKSPACE_101_ID::equals)
            .count();
        final var page = 1;
        final var pageSize = 3;
        final var pageReq = PageRequest.of(page, pageSize, Sort.by("createdDate"));
        final var pageTypo = service.getTypoPage(pageReq, WORKSPACE_101_ID);
        assertThat(pageTypo.getTotalPages()).isEqualTo(totalSize / pageSize + page);
        assertThat(pageTypo.getTotalElements()).isEqualTo(totalSize);
        assertThat(pageTypo.getSize()).isEqualTo(pageSize);
        assertThat(pageTypo.getNumber()).isEqualTo(page);
        assertThat(pageTypo.getNumberOfElements()).isEqualTo(pageSize);
    }


    @ParameterizedTest
    @MethodSource("io.hexlet.typoreporter.test.factory.EntitiesFactory#getTypoIdsExist")
    void patchTypoWithEventNullValues(final Long id) {
        final var typoStatus = repository.findById(id).orElseThrow().getTypoStatus();
        final var newTypoStatus = service.updateTypoStatus(id, null).orElseThrow().typoStatus();
        assertThat(newTypoStatus).isEqualTo(typoStatus);
    }

    @ParameterizedTest
    @MethodSource("io.hexlet.typoreporter.test.factory.EntitiesFactory#getTypoIdsExist")
    void patchTypoEventResolveToResolved(final Long id) {
        final var typoNotInProgress = repository.findById(id)
            .map(Typo::getTypoStatus)
            .filter(not(IN_PROGRESS::equals))
            .isPresent();
        if (typoNotInProgress) {
            return;
        }
        final var patchedTypo = service.updateTypoStatus(id, RESOLVE).orElseThrow();

        assertThat(patchedTypo.typoStatus()).isEqualTo(RESOLVED);
    }

    @ParameterizedTest
    @MethodSource("io.hexlet.typoreporter.test.factory.EntitiesFactory#getTypoIdsExist")
    void patchTypoEventStartToReported(final Long id) {
        final var typoNotReported = repository.findById(id)
            .map(Typo::getTypoStatus)
            .filter(not(REPORTED::equals))
            .isPresent();
        if (typoNotReported) {
            return;
        }
        final var typoStatus = service.updateTypoStatus(id, START)
            .map(TypoInfo::typoStatus)
            .orElseThrow();
        assertThat(typoStatus).isEqualTo(IN_PROGRESS);
    }

    @ParameterizedTest
    @MethodSource("io.hexlet.typoreporter.test.factory.EntitiesFactory#getTypoIdsExist")
    void patchTypoEventRestartToCanceled(final Long id) {
        final var typoNotCanceled = repository.findById(id)
            .map(Typo::getTypoStatus)
            .filter(not(CANCELED::equals))
            .isPresent();
        if (typoNotCanceled) {
            return;
        }
        final var typoStatus = service.updateTypoStatus(id, RESTART)
            .map(TypoInfo::typoStatus)
            .orElseThrow();

        assertThat(typoStatus).isEqualTo(IN_PROGRESS);
    }

    @ParameterizedTest
    @MethodSource("io.hexlet.typoreporter.test.factory.EntitiesFactory#getTypoIdsExist")
    void deleteTypoById(final Long id) {
        assertThat(repository.existsById(id)).isTrue();
        assertThat(service.deleteTypoById(id)).isNotZero();
        assertThat(repository.existsById(id)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("io.hexlet.typoreporter.test.factory.EntitiesFactory#getTypoIdsNotExist")
    void deleteTypoByIdNotFound(final Long id) {
        assertThat(repository.existsById(id)).isFalse();
        assertThat(service.deleteTypoById(id)).isZero();
        assertThat(repository.existsById(id)).isFalse();
    }

    @Test
    void getCountTypoByStatusForWorkspaceId() {

        List<Typo> listAll = repository.findAll();

        long uniqTypoStatus = listAll.stream()
            .filter(x -> x.getWorkspace().getId().equals(WORKSPACE_101_ID))
            .map(Typo::getTypoStatus)
            .distinct()
            .count();

        Long countReported = listAll.stream()
            .filter(x -> x.getWorkspace().getId().equals(WORKSPACE_101_ID))
            .filter(x -> x.getTypoStatus().equals(REPORTED)).count();

        Long countInProgress = listAll.stream()
            .filter(x -> x.getWorkspace().getId().equals(WORKSPACE_101_ID))
            .filter(x -> x.getTypoStatus().equals(IN_PROGRESS)).count();

        Long countResolved = listAll.stream()
            .filter(x -> x.getWorkspace().getId().equals(WORKSPACE_101_ID))
            .filter(x -> x.getTypoStatus().equals(RESOLVED)).count();

        Long countCanceled = listAll.stream()
            .filter(x -> x.getWorkspace().getId().equals(WORKSPACE_101_ID))
            .filter(x -> x.getTypoStatus().equals(CANCELED)).count();

        List<Pair<TypoStatus, Long>> listWks = service.getCountTypoByStatusForWorkspaceId(WORKSPACE_101_ID);
        assertThat(listWks.size()).isEqualTo((int) uniqTypoStatus);
        assertThat(listWks.stream())
            .filteredOn(x -> x.getLeft().equals(REPORTED) && x.getRight().equals(countReported))
            .hasSize(1);
        assertThat(listWks.stream())
            .filteredOn(x -> x.getLeft().equals(IN_PROGRESS) && x.getRight().equals(countInProgress))
            .hasSize(1);
        assertThat(listWks.stream())
            .filteredOn(x -> x.getLeft().equals(RESOLVED) && x.getRight().equals(countResolved))
            .hasSize(1);
        assertThat(listWks.stream())
            .filteredOn(x -> x.getLeft().equals(CANCELED) && x.getRight().equals(countCanceled))
            .hasSize(1);

    }

    @Test
    void getLastTypoByWorkspaceId() {
        Optional<Typo> Top = repository.findAll()
            .stream()
            .filter(x -> x.getWorkspace().getId().equals(WORKSPACE_101_ID))
            .sorted(Comparator.comparing(AbstractAuditingEntity::getCreatedDate))
            .limit(1L)
            .findFirst();

        Optional<TypoInfo> typoInfo = service.getLastTypoByWorkspaceId(WORKSPACE_101_ID);
        assertThat(typoInfo).isNotEmpty();
        assertThat(typoInfo.get().id()).isEqualTo(Top.get().getId());
        Optional<TypoInfo> typoInfo2 = service.getLastTypoByWorkspaceId(WORKSPACE_103_ID);
        assertThat(typoInfo2).isEmpty();
    }
}
