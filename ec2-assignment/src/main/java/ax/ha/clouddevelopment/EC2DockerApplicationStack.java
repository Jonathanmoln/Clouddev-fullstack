package ax.ha.clouddevelopment;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.SecretValue;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.elasticloadbalancingv2.AddApplicationTargetsProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListener;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.BaseApplicationListenerProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.targets.InstanceTarget;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.rds.*;
import software.amazon.awscdk.services.route53.*;
import software.amazon.awscdk.services.route53.targets.LoadBalancerTarget;
import software.constructs.Construct;

import java.util.Arrays;

public class EC2DockerApplicationStack extends Stack {

    // Do not remove these variables. The hosted zone can be used later when creating DNS records
    private final IHostedZone hostedZone = HostedZone.fromHostedZoneAttributes(this, "HaHostedZone", HostedZoneAttributes.builder()
            .hostedZoneId("Z0413857YT73A0A8FRFF")
            .zoneName("cloud-ha.com")
            .build());

    // Do not remove, you can use this when defining what VPC your security group, instance and load balancer should be part of.
    private final IVpc vpc = Vpc.fromLookup(this, "MyVpc", VpcLookupOptions.builder()
            .isDefault(true)
            .build());

    public EC2DockerApplicationStack(final Construct scope, final String id, final StackProps props, final String groupName) {
        super(scope, id, props);

        // Create a Security Group within the specified VPC
        SecurityGroup mySecurityGroup = SecurityGroup.Builder.create(this, "SecurityGroup")
                .vpc(vpc) // Associate the security group with the VPC
                .allowAllOutbound(true) // Allow all outbound traffic from this security group
                .build();

        // Add ingress rules to the security group to allow HTTP and HTTPS traffic
        mySecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(80), "Allow HTTP traffic"); // Allow inbound HTTP traffic on port 80
        mySecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(443), "Allow HTTPS traffic"); // Allow inbound HTTPS traffic on port 443

        // Create an IAM Role to be assumed by the EC2 service
        Role ec2Role = Role.Builder.create(this, "EC2Role")
                .assumedBy(new ServicePrincipal("ec2.amazonaws.com")) // Specify that this role can be assumed by EC2 instances
                .managedPolicies(Arrays.asList(
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore"), // Allow SSM access to the instance
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ContainerRegistryReadOnly") // Allow read-only access to ECR
                ))
                .build();

        // Create RDS PostgreSQL Database
        DatabaseInstance database = DatabaseInstance.Builder.create(this, "PostgresDB")
                // Set the database engine to PostgreSQL with version 15.6
                .engine(DatabaseInstanceEngine.postgres(PostgresInstanceEngineProps.builder()
                        .version(PostgresEngineVersion.VER_15_6)
                        .build()))
                // Specify the instance type to be a burstable class with micro size (cost-effective)
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                // Place the database instance within the specified VPC
                .vpc(vpc)
                // Set the credentials for the database
                .credentials(Credentials.fromPassword("master", SecretValue.unsafePlainText("mastermaster")))
                // Define the subnets where the database instance should be deployed, using private subnets with NAT
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PUBLIC)
                        .build())
                .build();


        // Create the EC2 Instance
        Instance ec2Instance = Instance.Builder.create(this, "MyEC2Instance")
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO)) // t3.micro instance type
                .machineImage(MachineImage.latestAmazonLinux2()) // Amazon Linux 2 with x86_64 architecture
                .role(ec2Role) // Attach the IAM role created earlier
                .vpc(vpc) // Specify the VPC
                .userDataCausesReplacement(true) // Forces instance to update data if updated
                .securityGroup(mySecurityGroup) // Attach the security group created earlier
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PUBLIC) // Use public subnets
                        .build())
                .build();

        // Add user data to the instance to run commands on startup
        ec2Instance.addUserData(
                "yum install docker -y",
                "sudo systemctl start docker",
                "aws ecr get-login-password --region eu-north-1 | docker login --username AWS --password-stdin 292370674225.dkr.ecr.eu-north-1.amazonaws.com",
                "docker run -d -e DB_URL=\"jonathan-rasse-ec2-assignment-postgresdb113281d2-9qfqlgsgsyme.chbvabsbak05.eu-north-1.rds.amazonaws.com\" -e DB_USERNAME=\"master\" -e DB_PASSWORD=\"mastermaster\" -e SPRING_PROFILES_ACTIVE=\"postgres\" --name my-application -p 80:8080 292370674225.dkr.ecr.eu-north-1.amazonaws.com/webshop-api:latest"
        );


        // Create an Application Load Balancer
        ApplicationLoadBalancer alb = ApplicationLoadBalancer.Builder.create(this, "ALB")
                .vpc(vpc) // Specifies that the load balancer should be placed in the specified VPC
                .internetFacing(true) // Makes the load balancer internet-facing, meaning it is accessible from the internet
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PUBLIC) // Specifies that the load balancer should be placed in public subnets
                        .build())
                .securityGroup(mySecurityGroup) // Links the previously created security group to the load balancer
                .build();

// Add a listener for HTTP traffic
        ApplicationListener listener = alb.addListener("Listener", BaseApplicationListenerProps.builder()
                .port(80) // Listen on port 80 (HTTP)
                .open(true) // Makes the listener accessible to the public (open to the internet)
                .build());

// Add targets for the listener
        listener.addTargets("TargetGroup", AddApplicationTargetsProps.builder()
                .targets(Arrays.asList(new InstanceTarget(ec2Instance))) // Specifies that the EC2 instance should be the target for incoming traffic
                .port(80) // Traffic is directed to port 80 on the EC2 instance
                .build());

        String recordName = "jonathan-rasse-api.cloud-ha.com";
        // Create a RecordSet with type A
        ARecord.Builder.create(this, "MyRecordSet")
                .zone(HostedZone.fromHostedZoneAttributes(this, "MyHostedZone", HostedZoneAttributes.builder()
                        .hostedZoneId("Z0413857YT73A0A8FRFF") // Specify your hosted zone ID
                        .zoneName("cloud-ha.com") // Specify your hosted zone domain name
                        .build()))
                .recordName(recordName) // Set the record name
                .target(RecordTarget.fromAlias(new LoadBalancerTarget(alb))) // Point the record set to the load balancer using an alias
                .build();
        // TODO: Define your cloud resources here.
    }
}