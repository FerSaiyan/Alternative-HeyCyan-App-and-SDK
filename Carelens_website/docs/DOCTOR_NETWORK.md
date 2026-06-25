# Doctor Network Draft

## Objective
Maintain a quality-controlled roster of partner doctors for consistent scheduling and documentation.

## MVP data model
- Doctor profile
- Specialty and licensing metadata
- Availability windows
- Consultation status updates
- Prescription outcome flag

## Workflow
1. Patient schedules slot.
2. Doctor completes consultation.
3. Doctor records outcome.
4. Operations receives next-step trigger.

## Scheduling source (current draft)
- Google Calendar is the first scheduling provider for MVP in Brazil.
- Use one dedicated calendar for available slots.
- Create availability events with summary prefix `LEVYA_SLOT` so the app can detect and display them.

## Compliance notes
- Keep only minimum required health-related data in app surface.
- Keep detailed clinical notes in approved secure systems.
