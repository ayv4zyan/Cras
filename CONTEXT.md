# Cras

Personal task management for one operator and their devices. Todoist-inspired; no Projects in this effort.

## Language

**Operator**:
The person whose task list a copy of Cras is for. One operator per copy.
_Avoid_: User, customer, tenant, owner

**Voice capture**:
An in-app recording the Operator makes so Cras can propose or change Task fields.
_Avoid_: dictation, OpenWhispr (OS-level paste, not this product)

**Draft**:
A proposed Task, or a proposed change to a Task, shown after Voice capture. Not saved until the Operator accepts.
_Avoid_: pending task, suggestion
