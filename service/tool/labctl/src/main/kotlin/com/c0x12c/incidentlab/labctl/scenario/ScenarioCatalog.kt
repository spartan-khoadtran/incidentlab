package com.c0x12c.incidentlab.labctl.scenario

import com.c0x12c.incidentlab.labctl.scenario.catalog.AppScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.AsyncScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.ContainerHostScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.DatabaseScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.DownstreamScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.EdgeScenarios
import com.c0x12c.incidentlab.labctl.scenario.catalog.LoadScenarios

/**
 * Catalog of lab incidents, one file per layer under `catalog/`. Spoiler warning: those files hold
 * the answers. Try the exercises first.
 *
 * Each scenario draws random parameters, turns them into fault overrides per service, and carries
 * the ticket, the hints, the accepted answer and the explanation with its PromQL evidence.
 */
object ScenarioCatalog {

  val all: List<Scenario> =
    LoadScenarios.all + EdgeScenarios.all + AppScenarios.all + DatabaseScenarios.all + DownstreamScenarios.all + AsyncScenarios.all + ContainerHostScenarios.all

  val byId: Map<String, Scenario> = all.associateBy { it.id }
}
