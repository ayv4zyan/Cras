import React, { useMemo } from "react";
import type { Label } from "../contracts/task";

export interface TaskLabelBadgesProps {
  readonly labelIds: readonly string[];
  readonly labels?: readonly Label[];
}

export function TaskLabelBadges({
  labelIds,
  labels = [],
}: TaskLabelBadgesProps): React.JSX.Element | null {
  const labelMap = useMemo(() => {
    return new Map(labels.map((l) => [l.id, l]));
  }, [labels]);

  if (labelIds.length === 0) return null;

  return (
    <div className="flex items-center flex-wrap gap-1 pt-0.5">
      {labelIds.map((labelId) => {
        const label = labelMap.get(labelId);
        if (!label) return null;
        return (
          <span
            key={label.id}
            className="inline-flex items-center space-x-1 px-1.5 py-0.5 rounded-sm text-[10px] font-medium bg-secondary text-foreground border border-border/50"
          >
            <span
              className="h-1.5 w-1.5 rounded-full shrink-0"
              style={{ backgroundColor: label.color }}
            />
            <span className="truncate max-w-[100px]">{label.name}</span>
          </span>
        );
      })}
    </div>
  );
}
