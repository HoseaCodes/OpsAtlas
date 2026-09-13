/**
 * One example manifest, used by both the "Fill in an example" button and the
 * generated prompt.
 *
 * There is deliberately one copy. An example that disagrees with itself between
 * the form and the prompt would teach two different shapes, and the one in the
 * prompt is the one a model imitates most closely.
 */
export const EXAMPLE_MANIFEST = `apiVersion: opsatlas.ambitiousconcepts.io/v1
kind: Service
metadata:
  name: orders-api
  displayName: Orders API
  owner: ambitious-concepts
  repository: ambitious-concepts/orders-api
spec:
  tier: 1
  runtime: spring-boot
  environments:
    - name: production
      url: https://orders.example.com
  health:
    readiness: /actuator/health/readiness
    liveness: /actuator/health/liveness
  observability:
    serviceName: orders-api
  operations:
    slo:
      availability: 99.9
      window: 30d
    runbook: docs/runbook.md
  journeys:
    - Place an order
  dependencies:
    - name: postgres
      kind: datastore
`;
