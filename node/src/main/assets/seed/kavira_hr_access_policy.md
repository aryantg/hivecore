# Kavira HR data: who may see what

The default is deny. Salary fields are ctc_annual_inr, fixed_annual_inr, variable_target_inr, incentive_paid_fy2026_inr, monthly_travel_allowance_inr.

## Roles

- **md**: individual records all; aggregates all.
- **hr_admin**: individual records all; aggregates all.
- **hr_staff**: individual records all except Leadership and HR departments; aggregates all.
- **finance**: individual records none; aggregates all.
- **marketing**: individual records none; aggregates Sales department only.
- **sales_manager**: individual records none; aggregates own branch, Sales department only.
- **employee**: individual records self only; aggregates none.

## Aggregate rules

- The minimum group size for any average, count or sum is 5 people.
- Allowed functions: count, average, sum. Blocked functions: min, max, top_n, rank, list, because a maximum or a ranked list is always one person's salary.
- Averages are rounded to the nearest INR 1000.
- A query that names or excludes an individual is blocked, because comparing it with the full average would reveal that person's salary.
- A group that differs from a recent query by fewer than 5 people is blocked for the same reason.

## Execution

Each branch phone holds only hr_shards/hr_<BRANCH>.csv and returns (count, sum) per group, never rows. The anchor merges partials, then applies min_group_size to the merged group before answering.
