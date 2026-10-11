package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.BhodlTopBar
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.onb_module_budget
import io.bhodl.shared.resources.onb_module_budget_desc
import io.bhodl.shared.resources.onb_module_goals
import io.bhodl.shared.resources.onb_module_goals_desc
import io.bhodl.shared.resources.onb_module_investments
import io.bhodl.shared.resources.onb_module_investments_desc
import io.bhodl.shared.resources.onb_module_labs
import io.bhodl.shared.resources.onb_module_labs_desc
import io.bhodl.shared.resources.onb_module_manual
import io.bhodl.shared.resources.onb_module_manual_desc
import io.bhodl.shared.resources.onb_modules_footer
import io.bhodl.shared.resources.onb_modules_intro
import io.bhodl.shared.resources.onb_modules_title
import org.jetbrains.compose.resources.stringResource

@Composable
public fun ModulesScreen(
    onBack: () -> Unit,
) {
    val investments by SettingsStore.moduleInvestments
    val budgetBills by SettingsStore.moduleBudgetBills
    val savingsGoals by SettingsStore.moduleSavingsGoals
    val manualAssets by SettingsStore.moduleManualAssets
    val analyticsLabs by SettingsStore.moduleAnalyticsLabs

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .widthIn(max = 480.dp)
                .padding(horizontal = 32.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BhodlTopBar(title = stringResource(Res.string.onb_modules_title), onBack = onBack)

            Text(
                text = stringResource(Res.string.onb_modules_intro),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
            )

            ModuleRow(
                tag = ModulesTags.INVESTMENTS,
                title = stringResource(Res.string.onb_module_investments),
                description = stringResource(Res.string.onb_module_investments_desc),
                enabled = investments,
                onToggle = { SettingsStore.setModuleInvestments(it) },
            )
            ModuleRow(
                tag = ModulesTags.BUDGET_BILLS,
                title = stringResource(Res.string.onb_module_budget),
                description = stringResource(Res.string.onb_module_budget_desc),
                enabled = budgetBills,
                onToggle = { SettingsStore.setModuleBudgetBills(it) },
            )
            ModuleRow(
                tag = ModulesTags.SAVINGS_GOALS,
                title = stringResource(Res.string.onb_module_goals),
                description = stringResource(Res.string.onb_module_goals_desc),
                enabled = savingsGoals,
                onToggle = { SettingsStore.setModuleSavingsGoals(it) },
            )
            ModuleRow(
                tag = ModulesTags.MANUAL_ASSETS,
                title = stringResource(Res.string.onb_module_manual),
                description = stringResource(Res.string.onb_module_manual_desc),
                enabled = manualAssets,
                onToggle = { SettingsStore.setModuleManualAssets(it) },
            )
            ModuleRow(
                tag = ModulesTags.ANALYTICS_LABS,
                title = stringResource(Res.string.onb_module_labs),
                description = stringResource(Res.string.onb_module_labs_desc),
                enabled = analyticsLabs,
                onToggle = { SettingsStore.setModuleAnalyticsLabs(it) },
            )

            Text(
                text = stringResource(Res.string.onb_modules_footer),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
                modifier = Modifier.padding(top = 8.dp),
            )

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ModulesTags.BACK),
            ) { Text(stringResource(Res.string.action_back)) }
        }
    }
}

@Composable
private fun ModuleRow(
    tag: String,
    title: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BhodlTheme.colors.surface)
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = BhodlTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = BhodlTheme.colors.textPrimary,
            )
            Text(
                text = description,
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textSecondary,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
        )
    }
}

public object ModulesTags {
    public const val INVESTMENTS: String = "modules.investments"
    public const val BUDGET_BILLS: String = "modules.budget-bills"
    public const val SAVINGS_GOALS: String = "modules.savings-goals"
    public const val MANUAL_ASSETS: String = "modules.manual-assets"
    public const val ANALYTICS_LABS: String = "modules.analytics-labs"
    public const val BACK: String = "modules.back"
}
