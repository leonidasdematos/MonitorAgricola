# MonitorAgricola

## Gateway RTK

O repositório agora conta com uma camada `hardware.gateway` para consumir as
coordenadas filtradas vindas do gateway RTK. A classe
`GatewayCoordinateStream` abstrai a assinatura no barramento existente,
normaliza os pacotes recebidos e disponibiliza callbacks com a taxa dinâmica
estimada. A ponte `MapCoordinateBridge` mantém o último ponto disponível para o
módulo de mapas, facilitando a futura remoção do GPS embarcado no dispositivo.