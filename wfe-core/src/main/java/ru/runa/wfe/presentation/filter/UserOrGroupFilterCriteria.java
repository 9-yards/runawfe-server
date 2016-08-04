package ru.runa.wfe.presentation.filter;

import java.util.Map;

import ru.runa.wfe.presentation.hibernate.QueryParameter;

public class UserOrGroupFilterCriteria extends FilterCriteria {
    private static final long serialVersionUID = 1L;

    public UserOrGroupFilterCriteria() {
        super(2);
    }

    @Override
    public String buildWhereCondition(String aliasedFieldName, Map<String, QueryParameter> placeholders) {
        boolean includeGroup = false;
        if (!getFilterTemplate(1).isEmpty()) {
            includeGroup = 1 == Integer.parseInt(getFilterTemplate(1));
        }

        final StringLikeFilter likeFilter = StringFilterCriteria.calcUseLike(getFilterTemplate(0));
        final String alias = makePlaceHolderName(aliasedFieldName);
        final StringBuilder paramStringBuilder = new StringBuilder();
        if (likeFilter.isUseLike()) {
            paramStringBuilder.append(" like ");
        } else {
            paramStringBuilder.append(" = ");
        }
        paramStringBuilder.append(":");
        paramStringBuilder.append(alias);
        placeholders.put(alias, new QueryParameter(alias, likeFilter.getSearchFilter()));

        final StringBuilder whereStringBuilder = new StringBuilder(aliasedFieldName);
        if (includeGroup) {
            whereStringBuilder.append(" in ( select distinct egm.executor.name from ru.runa.wfe.user.ExecutorGroupMembership as egm, "
                    + "ru.runa.wfe.user.ExecutorGroupMembership as egm2 where egm.group = egm2.group and (egm2.executor.name");
            whereStringBuilder.append(paramStringBuilder);
            whereStringBuilder.append(" or egm2.group.name ");
            whereStringBuilder.append(paramStringBuilder);
            whereStringBuilder.append("))");
        } else {
            whereStringBuilder.append(paramStringBuilder);
        }
        return whereStringBuilder.toString();
    }
}
