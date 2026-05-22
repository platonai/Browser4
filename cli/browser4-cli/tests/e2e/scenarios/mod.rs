pub(crate) mod batch;
pub(crate) mod browser;
pub(crate) mod mock_server;

use super::*;

pub(super) type ScenarioFn = fn(&mut E2ECtx);

#[derive(Clone, Copy)]
pub(super) struct ScenarioDef {
    pub(super) name: &'static str,
    pub(super) short_name: &'static str,
    pub(super) requires_browser4: bool,
    pub(super) restart_browser4: bool,
    pub(super) test_count: usize,
    pub(super) test_fn: ScenarioFn,
}

impl ScenarioDef {
    pub(super) fn effective_test_count(self) -> usize {
        self.test_count.max(1)
    }

    pub(super) fn is_batch_command_scenario(self) -> bool {
        self.name.contains("_batch_") || self.short_name.contains("batch")
    }
}

pub(crate) const SCENARIOS: &[ScenarioDef] = &[
    ScenarioDef {
        name: "test_e2e_session_lifecycle",
        short_name: "test_session_lifecycle",
        requires_browser4: true,
        restart_browser4: false,
        test_count: 1,
        test_fn: browser::test_session_lifecycle,
    },
    ScenarioDef {
        name: "test_e2e_newly_opened_session_shows_active",
        short_name: "test_newly_opened_session_shows_active",
        requires_browser4: true,
        restart_browser4: false,
        test_count: 1,
        test_fn: browser::test_newly_opened_session_shows_active,
    },
    ScenarioDef {
        name: "test_e2e_navigation_and_storage",
        short_name: "test_navigation_and_storage",
        requires_browser4: true,
        restart_browser4: false,
        test_count: 1,
        test_fn: browser::test_navigation_and_storage,
    },
    ScenarioDef {
        name: "test_e2e_interaction_commands",
        short_name: "test_interaction_commands",
        requires_browser4: true,
        restart_browser4: false,
        test_count: 1,
        test_fn: browser::test_interaction_commands,
    },
    ScenarioDef {
        name: "test_e2e_eval_command",
        short_name: "test_eval_command",
        requires_browser4: true,
        restart_browser4: false,
        test_count: 1,
        test_fn: browser::test_eval_command,
    },
    ScenarioDef {
        name: "test_e2e_agent_run_live_or_missing_llm_key",
        short_name: "test_agent_run_live_or_missing_llm_key",
        requires_browser4: true,
        restart_browser4: false,
        test_count: 1,
        test_fn: browser::test_agent_run_live_or_missing_llm_key,
    },
    ScenarioDef {
        name: "test_e2e_wait_for_state_failure_modes",
        short_name: "test_wait_for_state_failure_modes",
        requires_browser4: true,
        restart_browser4: false,
        test_count: 1,
        test_fn: browser::test_wait_for_state_failure_modes,
    },
    ScenarioDef {
        name: "test_e2e_form_controls_and_exports",
        short_name: "test_form_controls_and_exports",
        requires_browser4: true,
        restart_browser4: false,
        test_count: 1,
        test_fn: browser::test_form_controls_and_exports,
    },
    ScenarioDef {
        name: "test_e2e_mouse_and_dialog",
        short_name: "test_mouse_and_dialog",
        requires_browser4: true,
        restart_browser4: false,
        test_count: 1,
        test_fn: browser::test_mouse_and_dialog,
    },
    ScenarioDef {
        name: "test_e2e_tab_commands",
        short_name: "test_tab_commands",
        requires_browser4: true,
        restart_browser4: false,
        test_count: 1,
        test_fn: browser::test_tab_commands,
    },
    ScenarioDef {
        name: "test_e2e_batch_commands",
        short_name: "test_batch_commands",
        requires_browser4: true,
        restart_browser4: false,
        test_count: 1,
        test_fn: batch::test_batch_commands,
    },
    ScenarioDef {
        name: "test_e2e_batch_form_submission",
        short_name: "test_batch_form_submission",
        requires_browser4: true,
        restart_browser4: false,
        test_count: 1,
        test_fn: batch::test_batch_form_submission,
    },
    ScenarioDef {
        name: "test_e2e_batch_form_submission_from_json_file",
        short_name: "test_batch_form_submission_from_json_file",
        requires_browser4: true,
        restart_browser4: false,
        test_count: 1,
        test_fn: batch::test_batch_form_submission_from_json_file,
    },
    ScenarioDef {
        name: "test_e2e_batch_multi_interaction",
        short_name: "test_batch_multi_interaction",
        requires_browser4: true,
        restart_browser4: false,
        test_count: 1,
        test_fn: batch::test_batch_multi_interaction,
    },
    ScenarioDef {
        name: "test_e2e_batch_error_handling",
        short_name: "test_batch_error_handling",
        requires_browser4: true,
        restart_browser4: false,
        test_count: 1,
        test_fn: batch::test_batch_error_handling,
    },
    ScenarioDef {
        name: "test_e2e_batch_json_edge_cases",
        short_name: "test_batch_json_edge_cases",
        requires_browser4: true,
        restart_browser4: false,
        test_count: 1,
        test_fn: batch::test_batch_json_edge_cases,
    },
    ScenarioDef {
        name: "test_e2e_collective_session_and_agent_tools",
        short_name: "test_collective_session_and_agent_tools",
        requires_browser4: false,
        restart_browser4: false,
        test_count: 1,
        test_fn: mock_server::test_collective_session_and_agent_tools,
    },
    ScenarioDef {
        name: "test_e2e_open_uses_temporary_profile_mode",
        short_name: "test_open_uses_temporary_profile_mode",
        requires_browser4: false,
        restart_browser4: false,
        test_count: 1,
        test_fn: mock_server::test_open_uses_temporary_profile_mode,
    },
    ScenarioDef {
        name: "test_e2e_open_with_url_prints_page_state",
        short_name: "test_open_with_url_prints_page_state",
        requires_browser4: false,
        restart_browser4: false,
        test_count: 1,
        test_fn: mock_server::test_open_with_url_prints_page_state,
    },
    ScenarioDef {
        name: "test_e2e_open_reuses_existing_active_session",
        short_name: "test_open_reuses_existing_active_session",
        requires_browser4: false,
        restart_browser4: false,
        test_count: 1,
        test_fn: mock_server::test_open_reuses_existing_active_session,
    },
    ScenarioDef {
        name: "test_e2e_named_session_reuses_opened_session",
        short_name: "test_named_session_reuses_opened_session",
        requires_browser4: false,
        restart_browser4: false,
        test_count: 1,
        test_fn: mock_server::test_named_session_reuses_opened_session,
    },
    ScenarioDef {
        name: "test_e2e_open_refreshes_inactive_saved_session",
        short_name: "test_open_refreshes_inactive_saved_session",
        requires_browser4: false,
        restart_browser4: false,
        test_count: 1,
        test_fn: mock_server::test_open_refreshes_inactive_saved_session,
    },
    ScenarioDef {
        name: "test_e2e_open_reopens_saved_session_after_human_closed_tab",
        short_name: "test_open_reopens_saved_session_after_human_closed_tab",
        requires_browser4: false,
        restart_browser4: false,
        test_count: 1,
        test_fn: mock_server::test_open_reopens_saved_session_after_human_closed_tab,
    },
    ScenarioDef {
        name: "test_e2e_goto_requires_existing_active_session",
        short_name: "test_goto_requires_existing_active_session",
        requires_browser4: false,
        restart_browser4: false,
        test_count: 1,
        test_fn: mock_server::test_goto_requires_existing_active_session,
    },
    ScenarioDef {
        name: "test_e2e_batch_reduces_transport_round_trips",
        short_name: "test_batch_reduces_transport_round_trips",
        requires_browser4: false,
        restart_browser4: false,
        test_count: 1,
        test_fn: mock_server::test_batch_reduces_transport_round_trips,
    },
    ScenarioDef {
        name: "test_e2e_mock_eval_command",
        short_name: "test_mock_eval_command",
        requires_browser4: false,
        restart_browser4: false,
        test_count: 1,
        test_fn: mock_server::test_eval_command,
    },
    ScenarioDef {
        name: "test_e2e_mock_press_command_uses_direct_tool_dispatch",
        short_name: "test_mock_press_command_uses_direct_tool_dispatch",
        requires_browser4: false,
        restart_browser4: false,
        test_count: 1,
        test_fn: mock_server::test_press_command_uses_direct_tool_dispatch,
    },
    ScenarioDef {
        name: "test_e2e_agent_task_commands",
        short_name: "test_agent_task_commands",
        requires_browser4: false,
        restart_browser4: false,
        test_count: 1,
        test_fn: mock_server::test_agent_task_commands,
    },
    ScenarioDef {
        name: "test_e2e_agent_run_missing_llm_key",
        short_name: "test_agent_run_missing_llm_key",
        requires_browser4: false,
        restart_browser4: false,
        test_count: 1,
        test_fn: mock_server::test_agent_run_missing_llm_key,
    },
    ScenarioDef {
        name: "test_e2e_collective_submission_commands",
        short_name: "test_collective_submission_commands",
        requires_browser4: false,
        restart_browser4: false,
        test_count: 1,
        test_fn: mock_server::test_collective_submission_commands,
    },
    ScenarioDef {
        name: "test_e2e_collective_command_help_and_validation",
        short_name: "test_collective_command_help_and_validation",
        requires_browser4: false,
        restart_browser4: false,
        test_count: 1,
        test_fn: mock_server::test_collective_command_help_and_validation,
    },
];

pub(super) fn all_scenarios() -> &'static [ScenarioDef] {
    SCENARIOS
}
