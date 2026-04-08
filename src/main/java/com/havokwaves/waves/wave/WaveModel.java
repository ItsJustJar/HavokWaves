package com.havokwaves.waves.wave;

import com.havokwaves.waves.config.PluginConfig.WaveProfile;

public final class WaveModel {
    private static final double TWO_PI = Math.PI * 2.0D;

    public double sampleHeight(final WaveProfile profile, final double x, final double z, final long simulationTick) {
        return sample(profile, x, z, simulationTick, 1.0D).rawHeight();
    }

    public WaveSample sample(final WaveProfile profile, final double x, final double z, final long simulationTick) {
        return sample(profile, x, z, simulationTick, 1.0D);
    }

    public WaveSample sample(
            final WaveProfile profile,
            final double x,
            final double z,
            final long simulationTick,
            final double shorelineFactor
    ) {
        final double t = simulationTick / 20.0D;
        final double shoreline = clamp(shorelineFactor, 0.12D, 1.0D);
        final double shallow = 1.0D - shoreline;
        final double stormEnergy = stormEnergy(profile);
        final double freq = Math.max(0.06D, profile.frequency());
        final double spatialK = (TWO_PI / profile.wavelength()) * freq;
        final double shoalCompression = 1.0D + (shallow * (0.18D + (stormEnergy * 0.12D)));
        final double effectiveSpatialK = spatialK * shoalCompression;
        final double travelSpeed = profile.speed() * (0.42D + (freq * 0.62D));
        final double effectiveTravelSpeed = travelSpeed * (1.0D - (shallow * 0.22D));

        final double windAngle = (Math.PI * 0.11D) + (Math.sin(t * 0.006D) * 0.20D);
        final double windX = Math.cos(windAngle);
        final double windZ = Math.sin(windAngle);
        final double warpStrength = 2.0D + (stormEnergy * 1.6D);
        final double warpedX = x
            + (Math.sin((z * 0.018D) + (t * 0.31D)) * warpStrength)
            + (windX * t * 0.40D);
        final double warpedZ = z
            + (Math.cos((x * 0.016D) - (t * 0.28D)) * warpStrength)
            + (windZ * t * 0.40D);

        final double c1x = (Math.sin(t * 0.028D) * 92.0D) + (Math.cos(t * 0.017D) * 46.0D);
        final double c1z = (Math.cos(t * 0.024D) * 88.0D) - (Math.sin(t * 0.015D) * 39.0D);
        final double c2x = -114.0D + (Math.cos(t * 0.021D) * 74.0D);
        final double c2z = 83.0D + (Math.sin(t * 0.026D) * 67.0D);
        final double c3x = 162.0D + (Math.sin(t * 0.019D) * 58.0D);
        final double c3z = -137.0D + (Math.cos(t * 0.023D) * 61.0D);

        final double r1 = ovalRadius(warpedX, warpedZ, c1x, c1z, 1.35D, 0.82D);
        final double r2 = ovalRadius(warpedX, warpedZ, c2x, c2z, 0.95D, 1.22D);
        final double r3 = ovalRadius(warpedX, warpedZ, c3x, c3z, 1.18D, 1.04D);

        final double ring1 = Math.sin((r1 * effectiveSpatialK) - (t * effectiveTravelSpeed));
        final double ring2 = Math.sin((r2 * (effectiveSpatialK * 0.86D)) - (t * effectiveTravelSpeed * 0.84D));
        final double ring3 = Math.sin((r3 * (effectiveSpatialK * 0.76D)) - (t * effectiveTravelSpeed * 1.03D));
        final double radialField = (ring1 * 0.46D) + (ring2 * 0.31D) + (ring3 * 0.23D);

        final double a1 = (Math.PI * 0.24D) + (Math.sin(t * 0.017D) * 0.16D);
        final double a2 = (Math.PI * 0.97D) + (Math.cos(t * 0.015D) * 0.14D);
        final double a3 = (-Math.PI * 0.57D) + (Math.sin(t * 0.013D) * 0.18D);

        final double d1 = directionalComponent(warpedX, warpedZ, t, effectiveSpatialK, effectiveTravelSpeed, a1, 1.00D, 1.00D, 0.7D);
        final double d2 = directionalComponent(warpedX, warpedZ, t, effectiveSpatialK, effectiveTravelSpeed, a2, 0.88D, 0.91D, 2.1D);
        final double d3 = directionalComponent(warpedX, warpedZ, t, effectiveSpatialK, effectiveTravelSpeed, a3, 0.72D, 0.77D, 4.0D);
        final double directionalField = (d1 * 0.48D) + (d2 * 0.34D) + (d3 * 0.18D);

        final double counterA = directionalComponent(
                warpedX,
                warpedZ,
                t,
                effectiveSpatialK,
                effectiveTravelSpeed,
                a1 + (Math.PI * (0.88D + (stormEnergy * 0.10D))),
                1.05D,
                1.06D + (stormEnergy * 0.10D),
                1.85D
        );
        final double counterB = directionalComponent(
                warpedX,
                warpedZ,
                t,
                effectiveSpatialK,
                effectiveTravelSpeed,
                a2 - (Math.PI * (0.84D + (stormEnergy * 0.12D))),
                0.93D,
                0.96D + (stormEnergy * 0.10D),
                4.55D
        );
        final double collisionField = ((counterA * d2) + (counterB * d1)) * (0.08D + (stormEnergy * 0.28D));

        final double crossChop = Math.sin(
            ((warpedX * 0.061D) - (warpedZ * 0.054D)) * (effectiveSpatialK * 0.63D)
                - (t * effectiveTravelSpeed * 0.42D)
        ) * (0.07D + (stormEnergy * 0.16D));

        final double radialWeight = 0.60D - (stormEnergy * 0.10D);
        final double directionalWeight = 1.0D - radialWeight;
        final double mixedField = (radialField * radialWeight) + (directionalField * directionalWeight) + crossChop + collisionField;
        final double crestExponent = 1.82D - (stormEnergy * 0.34D);
        final double crestShape = copySignPow(mixedField, crestExponent);

        final double localRipple = Math.sin(
            ((warpedX * 0.083D) + (warpedZ * 0.052D)) * (effectiveSpatialK * 0.47D)
                - (t * effectiveTravelSpeed * 0.31D)
        )
                * (0.07D + (stormEnergy * 0.08D));

        final double packetField = packetField(warpedX, warpedZ, r1, r2, r3, t, freq);
        final double setField = waveSetEnvelope(warpedX, warpedZ, t, windAngle, effectiveSpatialK, effectiveTravelSpeed);
        final double coherenceField = (setField * 0.72D) + (packetField * 0.28D);
        final double occurrenceBoost = 1.0D + (stormEnergy * 0.24D);
        final double occurrence = clamp(
                profile.occurrence() * occurrenceBoost * (0.55D + shoreline * 0.45D),
                0.03D,
                0.995D
        );
        final double gateStart = 1.0D - occurrence;
        final double gateWidth = 0.18D + (stormEnergy * 0.06D);
        final double sporadicGate = smoothStep(gateStart, Math.min(0.998D, gateStart + gateWidth), coherenceField);

        final double localHeightScale = 1.0D
            + ((coherenceField - 0.5D) * (0.90D + (stormEnergy * 0.46D)) * profile.heightVariation());
        final double shorelineAmplitude = 0.25D + (shoreline * 0.75D);
        final double shoalingGain = 1.0D + (shallow * (0.22D + (stormEnergy * 0.14D)));
        final double amplitudeBoost = 1.0D + (stormEnergy * 0.35D);
        final double amplitude = profile.amplitude()
                * shorelineAmplitude
            * shoalingGain
                * amplitudeBoost
                * clamp(localHeightScale, 0.30D, 2.35D);

        final double breakerPulse = Math.max(0.0D, mixedField) * stormEnergy * 0.22D;
        double rawHeight = amplitude * (crestShape + localRipple + breakerPulse) * sporadicGate;
        final double breakerDissipation = 1.0D
            - (Math.max(0.0D, rawHeight) * shallow * (0.14D + (stormEnergy * 0.08D)));
        rawHeight *= clamp(breakerDissipation, 0.72D, 1.0D);
        if (rawHeight < 0.0D) {
            rawHeight *= Math.max(0.42D, 0.62D - (stormEnergy * 0.08D));
        } else {
            rawHeight *= 1.0D + (stormEnergy * 0.08D);
        }
        final double threshold = visualThreshold(profile);
        final int visualBand = rawHeight >= threshold ? 1 : (rawHeight <= -threshold ? -1 : 0);
        final double intensity = Math.abs(rawHeight) / Math.max(0.001D, threshold);
        return new WaveSample(rawHeight, threshold, visualBand, intensity, sporadicGate, shoreline);
    }

    public double visualThreshold(final WaveProfile profile) {
        return Math.max(0.04D, profile.amplitude() * 0.22D);
    }

    public double effectiveVisualHeight(
            final WaveProfile profile,
            final double rawHeight,
            final double maxOffset
    ) {
        if (Math.abs(rawHeight) < visualThreshold(profile)) {
            return 0.0D;
        }
        final double amplitudeScale = clamp(1.90D + ((profile.amplitude() - 0.15D) * 0.48D), 1.80D, 3.0D);
        final double scaled = rawHeight * amplitudeScale;
        return clamp(scaled, -maxOffset, maxOffset);
    }

    public int visualSteps(final WaveProfile profile, final double rawHeight) {
        final double threshold = visualThreshold(profile);
        final double absHeight = Math.abs(rawHeight);
        if (absHeight < threshold) {
            return 0;
        }

        final int sign = rawHeight >= 0.0D ? 1 : -1;
        final double secondStepThreshold = threshold * 2.1D;
        final double thirdStepThreshold = threshold * 3.35D;
        if (absHeight < secondStepThreshold) {
            return sign;
        }
        if (absHeight < thirdStepThreshold) {
            return sign * 2;
        }
        return sign * 3;
    }

    public double shorelineFactor(final int waterDepth) {
        if (waterDepth <= 1) {
            return 0.15D;
        }
        final double linear = clamp((waterDepth - 1.0D) / 10.0D, 0.0D, 1.0D);
        final double curved = linear * linear * (3.0D - (2.0D * linear));
        return 0.15D + (curved * 0.85D);
    }

    private double packetField(
            final double x,
            final double z,
            final double r1,
            final double r2,
            final double r3,
            final double t,
            final double frequency
    ) {
        final double freq = Math.max(0.05D, frequency);
        final double p1 = Math.sin((r1 * (0.029D * freq)) - (t * (0.017D + (freq * 0.010D))));
        final double p2 = Math.sin((r2 * (0.023D * freq)) - (t * (0.013D + (freq * 0.008D))) + 1.6D);
        final double p3 = Math.sin((r3 * (0.020D * freq)) - (t * (0.011D + (freq * 0.007D))) + 3.0D);
        final double p4 = Math.sin(((x * 0.010D) + (z * 0.007D)) * freq - (t * 0.028D));
        final double mixed = (p1 * 0.34D) + (p2 * 0.26D) + (p3 * 0.22D) + (p4 * 0.18D);
        return clamp(0.5D + (0.5D * mixed), 0.0D, 1.0D);
    }

    private double waveSetEnvelope(
            final double x,
            final double z,
            final double t,
            final double heading,
            final double spatialK,
            final double travelSpeed
    ) {
        final double along = (x * Math.cos(heading)) + (z * Math.sin(heading));
        final double cross = (x * -Math.sin(heading)) + (z * Math.cos(heading));

        final double e1 = Math.sin((along * spatialK * 0.10D) - (t * travelSpeed * 0.24D));
        final double e2 = Math.sin((along * spatialK * 0.067D) - (t * travelSpeed * 0.17D) + 1.8D);
        final double e3 = Math.sin((cross * spatialK * 0.058D) - (t * travelSpeed * 0.11D) + 0.9D);
        final double mixed = (e1 * 0.48D) + (e2 * 0.34D) + (e3 * 0.18D);
        return clamp(0.5D + (0.5D * mixed), 0.0D, 1.0D);
    }

    private double stormEnergy(final WaveProfile profile) {
        final double amp = clamp((profile.amplitude() - 0.30D) / 1.20D, 0.0D, 1.30D);
        final double speed = clamp((profile.speed() - 0.75D) / 2.40D, 0.0D, 1.20D);
        final double frequency = clamp((profile.frequency() - 0.90D) / 1.80D, 0.0D, 1.20D);
        return clamp((amp * 0.52D) + (speed * 0.30D) + (frequency * 0.18D), 0.0D, 1.25D);
    }

    private double directionalComponent(
            final double x,
            final double z,
            final double t,
            final double spatialK,
            final double travelSpeed,
            final double angle,
            final double spatialScale,
            final double speedScale,
            final double phase
    ) {
        final double proj = (x * Math.cos(angle)) + (z * Math.sin(angle));
        return Math.sin((proj * spatialK * spatialScale) - (t * travelSpeed * speedScale) + phase);
    }

    private double ovalRadius(
            final double x,
            final double z,
            final double centerX,
            final double centerZ,
            final double axisX,
            final double axisZ
    ) {
        final double dx = (x - centerX) / axisX;
        final double dz = (z - centerZ) / axisZ;
        return Math.sqrt((dx * dx) + (dz * dz));
    }

    private double smoothStep(final double edge0, final double edge1, final double value) {
        if (edge1 <= edge0) {
            return value >= edge0 ? 1.0D : 0.0D;
        }
        final double t = clamp((value - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - (2.0D * t));
    }

    private double copySignPow(final double value, final double exponent) {
        return Math.copySign(Math.pow(Math.abs(value), exponent), value);
    }

    private double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record WaveSample(
            double rawHeight,
            double threshold,
            int visualBand,
            double intensity,
            double sporadicGate,
            double shorelineFactor
    ) {
    }
}
